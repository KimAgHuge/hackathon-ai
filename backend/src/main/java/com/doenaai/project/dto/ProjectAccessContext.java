package com.doenaai.project.dto;

import java.util.UUID;

public record ProjectAccessContext(
        UUID projectId,
        UUID organizationId,
        String memberRole
) {

    public boolean hasRole(String... roles) {
        for (String role : roles) {
            if (memberRole.equals(role)) {
                return true;
            }
        }

        return false;
    }
}
