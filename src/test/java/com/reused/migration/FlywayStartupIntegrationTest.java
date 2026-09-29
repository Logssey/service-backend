package com.reused.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.reused.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayStartupIntegrationTest {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void bootMigratesFreshTestcontainersDatabaseUsingPackagedSql() {
		assertThat(Arrays.stream(flyway.info().applied())
				.map(info -> info.getVersion().toString())
				.toList()).containsExactly("1", "2", "3", "4", "5");
		assertThat(jdbc.queryForObject("SELECT to_regclass('public.users') IS NOT NULL", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL", Boolean.class))
				.isTrue();
		assertThat(jdbc.queryForObject(
				"SELECT enabled FROM service_feature_flags WHERE feature_key = 'CHATBOT'", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'listing_images'
				  AND column_name IN ('purpose', 'profile_user_id')
				""", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'user_identities'
				  AND column_name = 'email_consent_at'
				""", Integer.class)).isOne();
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

}
