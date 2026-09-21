package com.autotest.platform.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0L : count;
    }

    public UserAccount findByUsername(String username) {
        List<UserAccount> users = jdbc.query(
                "SELECT id, username, password_hash, revision FROM users WHERE username = ?",
                (rs, rowNum) -> new UserAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getInt("revision")),
                UsernameNormalizer.normalize(username));
        return users.isEmpty() ? null : users.get(0);
    }

    public void insert(String username, String passwordHash) {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, revision, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, ?, ?)",
                UUID.randomUUID(),
                UsernameNormalizer.normalize(username),
                passwordHash,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public int updatePassword(UUID id, String passwordHash) {
        return jdbc.update(
                "UPDATE users SET password_hash = ?, revision = revision + 1, updated_at = ? WHERE id = ?",
                passwordHash,
                Timestamp.from(Instant.now()),
                id);
    }
}
