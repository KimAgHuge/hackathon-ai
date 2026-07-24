package com.doenaai.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserAccessRepository {

    private final JdbcTemplate jdbcTemplate;

    public Set<String> findRoles(UUID userId) {

        String sql = """
                select distinct
                    r.code
                from public.user_roles ur
                join public.roles r
                  on r.id = ur.role_id
                where ur.user_id = ?
                order by r.code
                """;

        return new LinkedHashSet<>(
                jdbcTemplate.queryForList(
                        sql,
                        String.class,
                        userId
                )
        );
    }

    public Set<String> findPermissions(UUID userId) {

        String sql = """
                select distinct
                    p.code
                from public.user_roles ur
                join public.role_permissions rp
                  on rp.role_id = ur.role_id
                join public.permissions p
                  on p.id = rp.permission_id
                where ur.user_id = ?
                order by p.code
                """;

        return new LinkedHashSet<>(
                jdbcTemplate.queryForList(
                        sql,
                        String.class,
                        userId
                )
        );
    }
}