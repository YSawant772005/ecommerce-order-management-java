package com.ecommerce.ordermanagement.repository;

import com.ecommerce.ordermanagement.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** PostgreSQL access for users. Read-only. */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All customers for the Log In As dropdown, ordered by id. */
    public List<User> listUsers() {
        return jdbc.query("SELECT id, name, email FROM users ORDER BY id",
                (rs, n) -> new User(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        null));
    }
}
