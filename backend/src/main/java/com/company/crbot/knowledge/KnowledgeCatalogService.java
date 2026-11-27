package com.company.crbot.knowledge;

import com.company.crbot.common.ApiException;
import com.company.crbot.gitlab.GitlabClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeCatalogService {

    private final KbOwnerRepository ownerRepository;
    private final KbIterationRepository iterationRepository;
    private final KbDocRepository docRepository;
    private final KbDocOwnerRepository docOwnerRepository;
    private final GitlabClient gitlabClient;

    public KnowledgeCatalogService(KbOwnerRepository ownerRepository, KbIterationRepository iterationRepository,
                                   KbDocRepository docRepository, KbDocOwnerRepository docOwnerRepository,
                                   GitlabClient gitlabClient) {
        this.ownerRepository = ownerRepository;
        this.iterationRepository = iterationRepository;
        this.docRepository = docRepository;
        this.docOwnerRepository = docOwnerRepository;
        this.gitlabClient = gitlabClient;
    }

    public List<KbOwnerEntity> owners(Short role) {
        requireRole(role);
        return ownerRepository.findByRoleOrderByIdAsc(role);
    }

    @Transactional
    public KbOwnerEntity createOwner(String name, Short role, Long gitlabUserId) {
        requireRole(role);
        String trimmed = requireName(name);
        // 同一类人员里不重复建同名
        if (ownerRepository.existsByRoleAndName(role, trimmed)) {
            throw new ApiException("名称已存在");
        }
        KbOwnerEntity entity = new KbOwnerEntity();
        entity.setName(trimmed);
        entity.setRole(role);
        if (role == 2) {
            if (gitlabUserId == null) {
                throw new ApiException("请选择 Git 账号");
            }
            // 账号必须来自当前 GitLab，且不能重复绑定
            GitlabClient.GitUser user = gitlabClient.requireUser(gitlabUserId);
            if (ownerRepository.existsByGitlabUserId(gitlabUserId)) {
                throw new ApiException("该 Git 账号已关联其他开发人员");
            }
            entity.setGitlabUserId(user.id());
            entity.setGitlabUsername(user.username());
        } else if (gitlabUserId != null) {
            throw new ApiException("只有开发人员可以关联 Git 账号");
        }
        return ownerRepository.save(entity);
    }

    @Transactional
    public void deleteOwner(Long id) {
        if (!ownerRepository.existsById(id)) {
            throw new ApiException("人员不存在");
        }
        // 已被文档引用时不能删
        if (docOwnerRepository.existsByOwnerId(id)) {
            throw new ApiException("该人员已被知识文档使用");
        }
        ownerRepository.deleteById(id);
    }

    public List<KbIterationEntity> iterations() {
        return iterationRepository.findAllByOrderByIdAsc();
    }

    @Transactional
    public KbIterationEntity createIteration(String name) {
        String trimmed = requireName(name);
        if (iterationRepository.existsByName(trimmed)) {
            throw new ApiException("迭代版本已存在");
        }
        KbIterationEntity entity = new KbIterationEntity();
        entity.setName(trimmed);
        return iterationRepository.save(entity);
    }

    @Transactional
    public void deleteIteration(Long id) {
        if (!iterationRepository.existsById(id)) {
            throw new ApiException("迭代版本不存在");
        }
        if (docRepository.existsByIterationId(id)) {
            throw new ApiException("该迭代版本已被知识文档使用");
        }
        iterationRepository.deleteById(id);
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException("名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new ApiException("名称不能超过 64 字");
        }
        return trimmed;
    }

    private Short requireRole(Short role) {
        // 1产品负责人 2开发人员 3测试人员
        if (role == null || (role != 1 && role != 2 && role != 3)) {
            throw new ApiException("人员类型不正确");
        }
        return role;
    }
}
