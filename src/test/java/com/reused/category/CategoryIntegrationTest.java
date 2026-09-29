package com.reused.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("TRUNCATE categories RESTART IDENTITY CASCADE");
		jdbcTemplate.update("INSERT INTO categories (name, display_order, is_active) VALUES (?, ?, ?)",
				"비활성", 0, false);
		jdbcTemplate.update("INSERT INTO categories (name, display_order, is_active) VALUES (?, ?, ?)",
				"두 번째", 2, true);
		jdbcTemplate.update("INSERT INTO categories (name, display_order, is_active) VALUES (?, ?, ?)",
				"첫 번째", 1, true);
	}

	@Test
	@DisplayName("비로그인 사용자는 활성 카테고리를 표시 순서대로 조회한다")
	void anonymousUserGetsActiveCategoriesInDisplayOrder() throws Exception {
		mockMvc.perform(get("/api/v1/categories"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].categoryId").value(3))
				.andExpect(jsonPath("$[0].name").value("첫 번째"))
				.andExpect(jsonPath("$[0].displayOrder").doesNotExist())
				.andExpect(jsonPath("$[0].active").doesNotExist())
				.andExpect(jsonPath("$[1].categoryId").value(2))
				.andExpect(jsonPath("$[1].name").value("두 번째"));
	}

	@Test
	@DisplayName("활성 카테고리가 없으면 빈 배열을 반환한다")
	void emptyCategoriesReturnEmptyArray() throws Exception {
		jdbcTemplate.execute("UPDATE categories SET is_active = false");

		mockMvc.perform(get("/api/v1/categories"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

}
