package com.reused.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class FlywaySafetyIntegrationTest {

	@Test
	void nonEmptyUntrackedDatabaseIsNotAutomaticallyBaselined() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))) {
			postgres.start();
			try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
					var statement = connection.createStatement()) {
				statement.execute("CREATE TABLE preexisting_record (id BIGINT PRIMARY KEY)");
			}

			Flyway flyway = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.baselineOnMigrate(false)
					.load();
			assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
					.hasMessageContaining("non-empty schema");

			try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
					var statement = connection.createStatement()) {
				try (var rows = statement.executeQuery("""
						SELECT to_regclass('public.preexisting_record'), to_regclass('public.flyway_schema_history')
						""")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getString(1)).isEqualTo("preexisting_record");
					assertThat(rows.getString(2)).isNull();
				}
			}
		}
	}

}
