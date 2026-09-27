package com.reused.demo;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicitly opted-in sample marketplace, limited to a fresh loopback-only demo database. */
@Component
@Profile("local-demo")
@ConditionalOnProperty(name = "app.demo.seed", havingValue = "true")
public class LocalDemoSeeder implements ApplicationRunner {

	private static final String SELLER_EMAIL = "demo-seller@reused.invalid";
	private static final String BUYER_EMAIL = "demo-buyer@reused.invalid";
	private static final String ADMIN_EMAIL = "demo-admin@reused.invalid";
	private static final String SPEAKER_TITLE = "Demo Bluetooth Speaker";
	private static final String LAMP_TITLE = "Demo Desk Lamp";
	private static final String POST_TITLE = "Demo neighborhood exchange";
	private static final String COMMENT_TEXT = "I can pick it up this weekend.";
	private static final String MESSAGE_TEXT = "Is the desk lamp still available?";
	private static final String NOTICE_TITLE = "Demo marketplace notice";

	private final DataSource dataSource;
	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final String password;

	public LocalDemoSeeder(DataSource dataSource, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
			@Value("${app.demo.password:}") String password) {
		this.dataSource = dataSource;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws SQLException {
		requireSafeTarget();
		if (password == null || password.length() < 8 || password.length() > 128) {
			throw new IllegalStateException("APP_DEMO_PASSWORD must be a development-only value of 8-128 characters");
		}
		// Serialize two opt-in starts of the same demo database without changing the schema.
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('reused:local-demo-seed:v1', 0))",
				resultSet -> null);

		long seller = account(SELLER_EMAIL, "DemoSeller", "USER");
		long buyer = account(BUYER_EMAIL, "DemoBuyer", "USER");
		long admin = account(ADMIN_EMAIL, "DemoAdmin", "ADMIN");

		long digital = category("디지털기기");
		long furniture = category("가구/인테리어");
		long speaker = listing(seller, digital, SPEAKER_TITLE,
				"Local demo item: available for a new purchase flow.", 35000, "ON_SALE");
		long lamp = listing(seller, furniture, LAMP_TITLE,
				"Local demo item: an accepted trade ready to complete.", 12000, "RESERVED");
		long trade = acceptedTrade(lamp, seller, buyer);
		long room = chatRoom(lamp, seller, buyer, trade);
		message(room, buyer, MESSAGE_TEXT);

		long post = communityPost(seller, POST_TITLE,
				"This local demo post shows how community replies work.");
		communityComment(post, buyer, COMMENT_TEXT);
		report(buyer, speaker);
		notice(admin, NOTICE_TITLE, "This notice belongs only to the local demo database.");
	}

	private void requireSafeTarget() throws SQLException {
		String database = jdbc.queryForObject("SELECT current_database()", String.class);
		String url;
		try (Connection connection = dataSource.getConnection()) {
			url = connection.getMetaData().getURL();
		}
		if (!isAllowedTarget(url, database)) {
			throw new IllegalStateException("Demo seeding requires a loopback PostgreSQL URL and a reused_demo_* database");
		}
	}

	static boolean isAllowedTarget(String jdbcUrl, String database) {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")
				|| database == null || !database.matches("reused_demo_[a-z0-9_]+")) {
			return false;
		}
		try {
			String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
			return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
					|| "::1".equals(host) || "[::1]".equals(host);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private long account(String email, String nickname, String role) {
		List<Account> existing = jdbc.query("""
				SELECT u.user_id, u.nickname, u.role FROM user_identities i
				JOIN users u ON u.user_id = i.user_id
				WHERE i.provider = 'LOCAL' AND i.email = ?
				""", (rs, rowNum) -> new Account(rs.getLong("user_id"), rs.getString("nickname"), rs.getString("role")),
				email);
		if (!existing.isEmpty()) {
			Account account = existing.getFirst();
			if (!nickname.equals(account.nickname()) || !role.equals(account.role())) {
				throw new IllegalStateException("A demo email belongs to a different local account: " + email);
			}
			return account.id();
		}
		long id = jdbc.queryForObject("""
				INSERT INTO users(nickname, role, terms_agreed_at)
				VALUES (?, ?, now()) RETURNING user_id
				""", Long.class, nickname, role);
		jdbc.update("""
				INSERT INTO user_identities(user_id, provider, provider_user_id, email, password_hash, email_verified_at)
				VALUES (?, 'LOCAL', ?, ?, ?, now())
				""", id, UUID.randomUUID().toString(), email, passwordEncoder.encode(password));
		jdbc.update("INSERT INTO notification_settings(user_id) VALUES (?)", id);
		return id;
	}

	private long category(String name) {
		return jdbc.queryForObject("SELECT category_id FROM categories WHERE name = ?", Long.class, name);
	}

	private long listing(long seller, long category, String title, String description, int price, String status) {
		List<Long> ids = jdbc.query("SELECT listing_id FROM listings WHERE seller_id = ? AND title = ?",
				(rs, rowNum) -> rs.getLong(1), seller, title);
		if (!ids.isEmpty()) return uniqueId(ids, title);
		return jdbc.queryForObject("""
				INSERT INTO listings(seller_id, category_id, title, description, price, item_condition, trade_method, status)
				VALUES (?, ?, ?, ?, ?, 'USED', 'DIRECT', ?) RETURNING listing_id
				""", Long.class, seller, category, title, description, price, status);
	}

	private long acceptedTrade(long listing, long seller, long buyer) {
		List<Long> ids = jdbc.query("SELECT trade_id FROM trades WHERE listing_id = ? AND buyer_id = ?",
				(rs, rowNum) -> rs.getLong(1), listing, buyer);
		if (!ids.isEmpty()) return uniqueId(ids, "demo trade");
		long id = jdbc.queryForObject("""
				INSERT INTO trades(listing_id, seller_id, buyer_id, status, accepted_at)
				VALUES (?, ?, ?, 'ACCEPTED', now()) RETURNING trade_id
				""", Long.class, listing, seller, buyer);
		jdbc.update("""
				INSERT INTO trade_status_histories(trade_id, before_status, after_status, changed_by)
				VALUES (?, 'REQUESTED', 'ACCEPTED', ?)
				""", id, seller);
		return id;
	}

	private long chatRoom(long listing, long seller, long buyer, long trade) {
		List<Long> ids = jdbc.query("SELECT chat_room_id FROM chat_rooms WHERE listing_id = ? AND buyer_id = ?",
				(rs, rowNum) -> rs.getLong(1), listing, buyer);
		if (!ids.isEmpty()) return uniqueId(ids, "demo chat room");
		return jdbc.queryForObject("""
				INSERT INTO chat_rooms(listing_id, seller_id, buyer_id, trade_id)
				VALUES (?, ?, ?, ?) RETURNING chat_room_id
				""", Long.class, listing, seller, buyer, trade);
	}

	private void message(long room, long sender, String content) {
		Long count = jdbc.queryForObject("""
				SELECT count(*) FROM messages WHERE chat_room_id = ? AND sender_id = ? AND content = ?
				""", Long.class, room, sender, content);
		if (count != null && count > 0) return;
		jdbc.update("INSERT INTO messages(chat_room_id, sender_id, content) VALUES (?, ?, ?)", room, sender, content);
		jdbc.update("UPDATE chat_rooms SET last_message_at = now() WHERE chat_room_id = ?", room);
	}

	private long communityPost(long author, String title, String content) {
		List<Long> ids = jdbc.query("SELECT post_id FROM community_posts WHERE author_id = ? AND title = ?",
				(rs, rowNum) -> rs.getLong(1), author, title);
		if (!ids.isEmpty()) return uniqueId(ids, "demo community post");
		return jdbc.queryForObject("""
				INSERT INTO community_posts(author_id, category, title, content)
				VALUES (?, 'GENERAL', ?, ?) RETURNING post_id
				""", Long.class, author, title, content);
	}

	private void communityComment(long post, long author, String content) {
		Long count = jdbc.queryForObject("""
				SELECT count(*) FROM community_comments WHERE post_id = ? AND author_id = ? AND content = ?
				""", Long.class, post, author, content);
		if (count != null && count > 0) return;
		jdbc.update("INSERT INTO community_comments(post_id, author_id, content) VALUES (?, ?, ?)",
				post, author, content);
		jdbc.update("UPDATE community_posts SET comment_count = comment_count + 1 WHERE post_id = ?", post);
	}

	private void report(long reporter, long listing) {
		Long count = jdbc.queryForObject("""
				SELECT count(*) FROM reports WHERE reporter_id = ? AND target_type = 'LISTING'
				AND target_id = ? AND reason_code = 'OTHER'
				""", Long.class, reporter, listing);
		if (count != null && count > 0) return;
		jdbc.update("""
				INSERT INTO reports(reporter_id, target_type, target_id, reason_code, detail)
				VALUES (?, 'LISTING', ?, 'OTHER', 'Local demo report for admin workflow')
				""", reporter, listing);
	}

	private void notice(long admin, String title, String content) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM notices WHERE author_id = ? AND title = ?",
				Long.class, admin, title);
		if (count != null && count > 0) return;
		jdbc.update("INSERT INTO notices(author_id, title, content) VALUES (?, ?, ?)", admin, title, content);
	}

	private static long uniqueId(List<Long> ids, String label) {
		if (ids.size() != 1) {
			throw new IllegalStateException("Ambiguous existing " + label + " rows in the demo database");
		}
		return ids.getFirst();
	}

	private record Account(long id, String nickname, String role) {
	}

}
