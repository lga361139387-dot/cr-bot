package com.company.crbot.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RagSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public RagSearchService(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    public void replaceChunks(String ownerType, long ownerId, List<String> chunks, String metadataJson) {
        // 重建索引先删旧切片，避免新旧向量混在一起
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE owner_type = ? AND owner_id = ?", ownerType, ownerId);
        if (chunks.isEmpty()) {
            return;
        }
        List<float[]> vectors = embeddingClient.embed(chunks);
        for (int i = 0; i < chunks.size(); i++) {
            jdbcTemplate.update("""
                    INSERT INTO kb_chunk (owner_type, owner_id, chunk_index, content, metadata_json, embedding)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::vector)
                    """, ownerType, ownerId, i, chunks.get(i), metadataJson, EmbeddingClient.toVectorLiteral(vectors.get(i)));
        }
    }

    public void deleteChunks(String ownerType, long ownerId) {
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE owner_type = ? AND owner_id = ?", ownerType, ownerId);
    }

    public List<ChunkView> listChunks(String ownerType, long ownerId) {
        return jdbcTemplate.query("""
                SELECT chunk_index, content, metadata_json::text
                FROM kb_chunk
                WHERE owner_type = ? AND owner_id = ?
                ORDER BY chunk_index
                """, (rs, rowNum) -> new ChunkView(rs.getInt(1), rs.getString(2), rs.getString(3)), ownerType, ownerId);
    }

    public List<RagHit> search(String ownerType, List<Long> ownerIds, String query, int topK, Double minScore) {
        if (ownerIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] vector = embeddingClient.embed(List.of(query)).get(0);
        String literal = EmbeddingClient.toVectorLiteral(vector);
        String idList = ownerIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1");
        // ownerIds 来自库内主键，已是数字，拼进 IN 不会带入外部字符串
        String vectorLimit = topK < 0 ? "" : " LIMIT 30";
        List<RagHit> vectorHits = jdbcTemplate.query("""
                SELECT id, owner_id, chunk_index, content, metadata_json::text,
                       1 - (embedding <=> ?::vector) AS cosine
                FROM kb_chunk
                WHERE owner_type = ? AND owner_id IN (%s) AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                %s
                """.formatted(idList, vectorLimit), (rs, rowNum) -> hit(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getDouble(6), 0), literal, ownerType, literal);
        List<String> tokens = tokens(query);
        List<RagHit> keywordHits = List.of();
        if (!tokens.isEmpty()) {
            String like = "%" + tokens.get(0).replace("%", "") + "%";
            String keywordLimit = topK < 0 ? "" : " LIMIT 30";
            keywordHits = jdbcTemplate.query("""
                    SELECT id, owner_id, chunk_index, content, metadata_json::text
                    FROM kb_chunk
                    WHERE owner_type = ? AND owner_id IN (%s) AND content ILIKE ?
                    %s
                    """.formatted(idList, keywordLimit), (rs, rowNum) -> hit(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                    rs.getString(4), rs.getString(5), 0, 0), ownerType, like);
        }
        Map<Long, RagHit> merged = new LinkedHashMap<>();
        for (RagHit hit : vectorHits) {
            merged.put(hit.chunkId(), hit);
        }
        for (RagHit hit : keywordHits) {
            merged.putIfAbsent(hit.chunkId(), hit);
        }
        List<RagHit> scored = new ArrayList<>();
        for (RagHit hit : merged.values()) {
            double keyword = keywordScore(hit.content(), tokens);
            double score = hybrid(hit.cosine(), keyword);
            if (minScore != null && score < minScore) {
                continue;
            }
            scored.add(new RagHit(hit.chunkId(), hit.ownerId(), hit.chunkIndex(), hit.content(), hit.metadataJson(),
                    hit.cosine(), keyword, score, clauseId(ownerType, hit), titleOf(hit.metadataJson())));
        }
        scored.sort(Comparator.comparingDouble(RagHit::score).reversed());
        if (topK >= 0 && scored.size() > topK) {
            return scored.subList(0, topK);
        }
        return scored;
    }

    public static double hybrid(double cosine, double keyword) {
        // 规格：0.7 向量 + 0.3 关键词
        return 0.7 * cosine + 0.3 * keyword;
    }

    public static double keywordScore(String content, List<String> tokens) {
        if (tokens.isEmpty() || content == null) {
            return 0;
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (haystack.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / tokens.size();
    }

    public static List<String> tokens(String query) {
        String[] parts = query.split("[^\\p{L}\\p{N}_./-]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= 2 && part.length() <= 80) {
                tokens.add(part);
            }
            if (tokens.size() >= 40) {
                break;
            }
        }
        return tokens;
    }

    private RagHit hit(long chunkId, long ownerId, int chunkIndex, String content, String metadata, double cosine, double keyword) {
        return new RagHit(chunkId, ownerId, chunkIndex, content, metadata, cosine, keyword, 0, "", "");
    }

    private String clauseId(String ownerType, RagHit hit) {
        return ownerType + "-" + hit.ownerId() + "-" + hit.chunkIndex();
    }

    private String titleOf(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            return node.path("title").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    public record ChunkView(int chunkIndex, String content, String metadataJson) {
    }

    public record RagHit(long chunkId, long ownerId, int chunkIndex, String content, String metadataJson,
                         double cosine, double keywordScore, double score, String clauseId, String title) {
    }
}
