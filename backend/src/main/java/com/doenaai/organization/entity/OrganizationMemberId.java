package com.doenaai.organization.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class OrganizationMemberId implements Serializable {

    private UUID organizationId;
    private UUID userId;
}
