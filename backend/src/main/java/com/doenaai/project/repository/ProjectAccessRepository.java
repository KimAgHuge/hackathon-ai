package com.doenaai.project.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProjectAccessRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findMemberRole(
            UUID projectId,
            UUID userId
    ) {
        String sql = """
                select pm.member_role
                from public.project_members pm
                join public.projects p
                  on p.id = pm.project_id
                join public.organization_members om
                  on om.organization_id = p.organization_id
                 and om.user_id = pm.user_id
                 and om.status = 'ACTIVE'
                where pm.project_id = ?
                  and pm.user_id = ?
                """;

        List<String> result = jdbcTemplate.queryForList(
                sql,
                String.class,
                projectId,
                userId
        );

        return result.stream().findFirst();
    }

    public void addOwner(
            UUID projectId,
            UUID userId
    ) {
        String sql = """
                insert into public.project_members (
                    project_id,
                    user_id,
                    member_role
                )
                values (?, ?, 'OWNER')
                on conflict (project_id, user_id)
                do update
                   set member_role = 'OWNER'
                """;

        jdbcTemplate.update(
                sql,
                projectId,
                userId
        );
    }
}
