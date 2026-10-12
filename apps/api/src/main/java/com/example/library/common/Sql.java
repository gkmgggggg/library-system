package com.example.library.common;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.sql.Timestamp;

@Repository
public class Sql {
    private final NamedParameterJdbcTemplate jdbc;
    public Sql(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static Map<String,Object> params(Object... pairs) {
        Map<String,Object> map=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]);
        return map;
    }
    public List<Map<String,Object>> list(String query, Map<String,?> params) {
        return jdbc.query(query, params, (rs,n)-> {
            var row=new LinkedHashMap<String,Object>(); var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++) {
                Object value=rs.getObject(i);
                if(value instanceof UUID) value=value.toString();
                if(value instanceof java.sql.Date d) value=d.toLocalDate().toString();
                if(value instanceof Timestamp t) value=t.toInstant().toString();
                row.put(meta.getColumnLabel(i),value);
            }
            return row;
        });
    }
    public Map<String,Object> one(String query, Map<String,?> params) {
        var rows=list(query,params);
        if(rows.isEmpty()) throw new BusinessException(404,"NOT_FOUND","记录不存在");
        return rows.getFirst();
    }
    public int update(String query, Map<String,?> params) { return jdbc.update(query,params); }
    public long count(String query, Map<String,?> params) { return jdbc.queryForObject(query,params,Long.class); }
    public Map<String,Object> page(String query, String countQuery, Map<String,Object> params, int page, int size) {
        if(page<1 || (size!=20 && size!=50)) throw new BusinessException(400,"INVALID_PAGE","页码应大于零，每页只能为 20 或 50 条");
        params.put("limit",size);params.put("offset",(long)(page-1)*size);
        return Map.of("items",list(query+" LIMIT :limit OFFSET :offset",params),"total",count(countQuery,params),"page",page,"pageSize",size);
    }
    public static String contains(String value) { return "%"+value.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"; }
}
