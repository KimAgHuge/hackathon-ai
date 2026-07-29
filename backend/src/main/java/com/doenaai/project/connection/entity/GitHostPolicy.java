package com.doenaai.project.connection.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Entity
@Table(
        name = "git_host_policies",
        schema = "public"
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHostPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String host;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    private RepositoryProviderType providerType;

    @Column(name = "https_enabled", nullable = false)
    private boolean httpsEnabled;

    @Column(name = "https_port", nullable = false)
    private int httpsPort;

    @Column(name = "ssh_enabled", nullable = false)
    private boolean sshEnabled;

    @Column(name = "ssh_port", nullable = false)
    private int sshPort;

    @Column(nullable = false)
    private boolean enabled;
}
