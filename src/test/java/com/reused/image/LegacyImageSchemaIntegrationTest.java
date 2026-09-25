package com.reused.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.repository.ImageRepository;
import com.reused.image.service.ImageCleanupService;
import com.reused.user.entity.UserRole;

@Import({TestcontainersConfiguration.class, ImageIntegrationTest.FakeStorageConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class LegacyImageSchemaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider tokens;
    @Autowired ImageRepository images;
    @Autowired ImageCleanupService cleanup;
    @Autowired ImageIntegrationTest.FakeImageStorage storage;
    @MockitoBean OAuthProviderClient kakaoClient;
    @MockitoBean AuthMailSender mailSender;

    @Test
    @Transactional // PostgreSQL rolls back the temporary schema downgrade with the entire test.
    void existingListingUploadsAndCleanupWorkBeforeOptionalProfileMigration() throws Exception {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        storage.reset();
        jdbc.execute("ALTER TABLE listing_images DROP COLUMN purpose, DROP COLUMN profile_user_id");
        assertThat(images.profilesEnabled()).isFalse();
        Long owner = jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES ('기존회원', now()) RETURNING user_id", Long.class);
        jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id) VALUES (?, 'KAKAO', 'legacy')", owner);
        String bearer = "Bearer " + tokens.issueAccessToken(owner, UserRole.USER);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        byte[] jpeg = bytes.toByteArray();
        var result = mvc.perform(post("/api/v1/images/upload-url").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                                "purpose", "LISTING", "fileName", "photo.jpg", "contentType", "image/jpeg", "fileSize", jpeg.length))))
                .andExpect(status().isCreated()).andReturn();
        Long id = mapper.readTree(result.getResponse().getContentAsString()).get("imageId").asLong();
        String key = jdbc.queryForObject("SELECT object_key FROM listing_images WHERE image_id = ?", String.class, id);
        storage.put(key, "image/jpeg", jpeg);
        mvc.perform(post("/api/v1/images/{id}/complete", id).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        assertThat(images.findForUpdate(id).orElseThrow().purpose()).isEqualTo("LISTING");
        assertThat(images.countUnattachedByUploader(owner)).isOne();
        jdbc.update("UPDATE listing_images SET created_at = now() - interval '2 days' WHERE image_id = ?", id);
        cleanup.cleanupExpiredOrphans();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE image_id = ?", Long.class, id)).isZero();
        mvc.perform(post("/api/v1/images/upload-url").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                                "purpose", "PROFILE", "fileName", "photo.jpg", "contentType", "image/jpeg", "fileSize", jpeg.length))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(patch("/api/v1/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":null}"))
                .andExpect(status().isServiceUnavailable());
    }
}
