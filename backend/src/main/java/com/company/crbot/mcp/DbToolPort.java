package com.company.crbot.mcp;

import java.util.List;

public interface DbToolPort {

    List<String> listTables(String schema);

    String describeTable(String table);

    String listIndexes(String table);

    String explainSql(String sql);
}
