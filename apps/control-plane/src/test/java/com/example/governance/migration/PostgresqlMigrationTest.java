package com.example.governance.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlMigrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "POSTGRES_MIGRATION_TEST_URL", matches = ".+")
    void upgrades_existing_v3_data_to_v4_without_loss() throws Exception {
        String url = requiredEnvironment("POSTGRES_MIGRATION_TEST_URL");
        String username = requiredEnvironment("POSTGRES_MIGRATION_TEST_USERNAME");
        String password = requiredEnvironment("POSTGRES_MIGRATION_TEST_PASSWORD");

        Flyway v3 = Flyway.configure()
                .dataSource(url, username, password)
                .target("3")
                .load();
        v3.migrate();
        assertThat(v3.info().current().getVersion().getVersion()).isEqualTo("3");

        insertV3Fixture(url, username, password);

        Flyway latest = Flyway.configure()
                .dataSource(url, username, password)
                .load();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("4");
        assertFixtureAndNullableImportPath(url, username, password);
    }

    private static void insertV3Fixture(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO capabilities (id, department, type)
                    VALUES ('upgrade-agent', 'customer-operations', 'AGENT')
                    """);
            statement.executeUpdate("""
                    INSERT INTO releases (
                        id, capability_id, version, artifact_reference, digest, created_at, state,
                        entity_version, canonical_manifest, manifest_digest
                    ) VALUES (
                        'upgrade-agent:1.0.0', 'upgrade-agent', '1.0.0',
                        'oci://registry.example.internal/upgrade-agent@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        CURRENT_TIMESTAMP, 'DRAFT', 0, '{}',
                        'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO release_dependencies (
                        release_id, dependency_order, dependency_capability_id, dependency_type,
                        dependency_version, dependency_digest
                    ) VALUES (
                        'upgrade-agent:1.0.0', 0, 'shared-skill', 'SKILL', '2.0.0',
                        'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
                    )
                    """);
        }
    }

    private static void assertFixtureAndNullableImportPath(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement dependency = connection.prepareStatement("""
                     SELECT dependency_type, dependency_import_path
                     FROM release_dependencies
                     WHERE release_id = 'upgrade-agent:1.0.0' AND dependency_order = 0
                     """);
             ResultSet result = dependency.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("dependency_type")).isEqualTo("SKILL");
            assertThat(result.getString("dependency_import_path")).isNull();
        }

        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement column = connection.prepareStatement("""
                     SELECT is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'release_dependencies'
                       AND column_name = 'dependency_import_path'
                     """);
             ResultSet result = column.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("is_nullable")).isEqualTo("YES");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the PostgreSQL migration test.");
        }
        return value;
    }
}
