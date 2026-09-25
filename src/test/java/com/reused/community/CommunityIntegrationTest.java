package com.reused.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityIntegrationTest {
    private static final String POSTS = "/api/v1/community/posts";
    private static final String VALID_POST = """
            {"category":"QUESTION","title":"중고 노트북 사용 문의","content":"배터리 상태는 어떻게 확인하나요?"}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private ObjectMapper json;
    @MockitoBean private OAuthProviderClient oauth;
    @MockitoBean private AuthMailSender mail;
    @MockitoBean private ImageStorage imageStorage;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
    }

    @Test
    void publicFeedDetailAndCommentsWorkWithRealDatabase() throws Exception {
        long author = user("작성자");
        long postId = createPost(author, VALID_POST);
        mvc.perform(get(POSTS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value(postId))
                .andExpect(jsonPath("$.items[0].category").value("QUESTION"))
                .andExpect(jsonPath("$.items[0].author.nickname").value("작성자"))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[0].viewCount").value(0));
        mvc.perform(get(POSTS + "/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.viewCount").value(1));
        mvc.perform(get(POSTS + "/{id}", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewCount").value(2));
        mvc.perform(get(POSTS + "/{id}/comments", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT view_count FROM community_posts WHERE post_id = ?",
                Integer.class, postId)).isEqualTo(2);
        mvc.perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(VALID_POST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanPartiallyEditAndDeleteButOtherUsersAndAdminCannotWrite() throws Exception {
        long author = user("작성자");
        long stranger = user("타인");
        long admin = user("관리자");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", admin);
        long postId = createPost(author, VALID_POST);
        mvc.perform(patch(POSTS + "/{id}", postId).header("Authorization", bearer(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"다른 제목\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(POSTS + "/{id}", postId).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  수정한 제목  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정한 제목"))
                .andExpect(jsonPath("$.content").value("배터리 상태는 어떻게 확인하나요?"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(post(POSTS).header("Authorization", bearer(admin, UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_POST))
                .andExpect(status().isForbidden());
        mvc.perform(get(POSTS).header("Authorization", bearer(admin, UserRole.ADMIN)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", author);
        mvc.perform(delete(POSTS + "/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE user_id = ?", author);
        mvc.perform(delete(POSTS + "/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());
        mvc.perform(get(POSTS + "/{id}", postId)).andExpect(status().isNotFound());
        mvc.perform(get(POSTS)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM community_posts WHERE post_id = ?",
                Long.class, postId)).isEqualTo(author);
    }

    @Test
    void feedCursorCategoryBlockAndWithdrawnAnonymizationAreCorrect() throws Exception {
        long first = user("첫작성자");
        long second = user("둘작성자");
        long viewer = user("조회자");
        Instant at = Instant.parse("2026-09-25T01:02:03Z");
        long oldest = rawPost(first, "GENERAL", at);
        long middle = rawPost(second, "QUESTION", at);
        long newest = rawPost(first, "QUESTION", at);
        MvcResult firstPage = mvc.perform(get(POSTS).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(newest))
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        String cursor = json.readTree(firstPage.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get(POSTS).param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(middle));
        mvc.perform(get(POSTS).param("category", "GENERAL").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get(POSTS).param("category", "GENERAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(oldest));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", viewer, first);
        mvc.perform(get(POSTS).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].postId").value(middle));
        // Direct links remain readable when the viewer blocks the author.
        mvc.perform(get(POSTS + "/{id}", newest).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", second);
        mvc.perform(get(POSTS + "/{id}", middle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.author.userId").doesNotExist())
                .andExpect(jsonPath("$.author.nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.isMine").value(false));
    }

    @Test
    void commentCounterOwnershipCursorAndPostVisibilityAreConsistent() throws Exception {
        long author = user("원글작성자");
        long commenter = user("댓글작성자");
        long other = user("다른댓글러");
        long postId = createPost(author, VALID_POST);
        long otherPost = createPost(author, VALID_POST);
        long first = createComment(postId, commenter, "  첫 번째 댓글  ");
        long second = createComment(postId, other, "두 번째 댓글");
        MvcResult page = mvc.perform(get(POSTS + "/{id}/comments", postId).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].commentId").value(second))
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        String cursor = json.readTree(page.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get(POSTS + "/{id}/comments", postId).param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].commentId").value(first))
                .andExpect(jsonPath("$.items[0].content").value("첫 번째 댓글"));
        mvc.perform(get(POSTS + "/{id}/comments", otherPost).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", author, other);
        mvc.perform(get(POSTS + "/{id}/comments", postId).header("Authorization", bearer(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].commentId").value(first));
        // The counter tracks all public comments, including ones filtered for this viewer.
        assertCount(postId, 2);
        mvc.perform(delete(POSTS + "/{id}/comments/{commentId}", otherPost, first)
                .header("Authorization", bearer(commenter))).andExpect(status().isNotFound());
        mvc.perform(delete(POSTS + "/{id}/comments/{commentId}", postId, first)
                .header("Authorization", bearer(other))).andExpect(status().isForbidden());
        assertCount(postId, 2);
        mvc.perform(delete(POSTS + "/{id}/comments/{commentId}", postId, first)
                .header("Authorization", bearer(commenter))).andExpect(status().isNoContent());
        mvc.perform(delete(POSTS + "/{id}/comments/{commentId}", postId, first)
                .header("Authorization", bearer(commenter))).andExpect(status().isNotFound());
        assertCount(postId, 1);
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", other);
        mvc.perform(get(POSTS + "/{id}/comments", postId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].author.userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 사용자"));
        jdbc.update("UPDATE community_posts SET status = 'HIDDEN' WHERE post_id = ?", postId);
        mvc.perform(get(POSTS + "/{id}", postId)).andExpect(status().isNotFound());
        mvc.perform(get(POSTS + "/{id}/comments", postId)).andExpect(status().isNotFound());
        mvc.perform(post(POSTS + "/{id}/comments", postId).header("Authorization", bearer(commenter))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"숨김 댓글\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputsAndWithdrawnOrSuspendedAuthorsAreRejected() throws Exception {
        long author = user("작성자");
        long postId = createPost(author, VALID_POST);
        for (String body : List.of("{}", "{\"category\":\"NOPE\",\"title\":\"제목\",\"content\":\"열 글자 넘는 내용입니다\"}",
                "{\"category\":\"TIP\",\"title\":\"x\",\"content\":\"본문이 충분한 길이입니다\"}")) {
            mvc.perform(post(POSTS).header("Authorization", bearer(author))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch(POSTS + "/{id}", postId).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(POSTS).param("category", "BAD")).andExpect(status().isBadRequest());
        mvc.perform(get(POSTS).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(POSTS).param("cursor", "bad-cursor")).andExpect(status().isBadRequest());
        mvc.perform(get(POSTS + "/0")).andExpect(status().isBadRequest());
        mvc.perform(post(POSTS + "/{id}/comments", postId).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", author);
        mvc.perform(post(POSTS + "/{id}/comments", postId).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"정지 중 댓글\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", author);
        mvc.perform(post(POSTS).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_POST)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentCommentsKeepCounterEqualToVisibleRows() throws Exception {
        long author = user("작성자");
        long postId = createPost(author, VALID_POST);
        List<String> users = List.of(bearer(user("첫댓글")), bearer(user("둘댓글")),
                bearer(user("셋댓글")), bearer(user("넷댓글")));
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String token : users) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return mvc.perform(post(POSTS + "/{id}/comments", postId).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"동시 댓글\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> result : results) assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertCount(postId, users.size());
    }

    private long createPost(long author, String body) throws Exception {
        MvcResult response = mvc.perform(post(POSTS).header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("postId").asLong();
    }

    private long createComment(long postId, long author, String content) throws Exception {
        MvcResult response = mvc.perform(post(POSTS + "/{id}/comments", postId)
                .header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CommunityController.CommentWriteRequest(content))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("commentId").asLong();
    }

    private long rawPost(long author, String category, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO community_posts (author_id, category, title, content, created_at)
                VALUES (?, ?, '테스트 제목', '테스트 본문 열 글자 이상입니다', ?) RETURNING post_id
                """, Long.class, author, category, Timestamp.from(createdAt));
    }

    private long user(String nickname) {
        return jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id",
                Long.class, nickname);
    }

    private String bearer(long userId) {
        return bearer(userId, UserRole.USER);
    }

    private String bearer(long userId, UserRole role) {
        return "Bearer " + tokens.issueAccessToken(userId, role);
    }

    private void assertCount(long postId, int count) {
        assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts WHERE post_id = ?",
                Integer.class, postId)).isEqualTo(count);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM community_comments
                WHERE post_id = ? AND deleted_at IS NULL AND status = 'PUBLISHED'
                """, Integer.class, postId)).isEqualTo(count);
    }
}
