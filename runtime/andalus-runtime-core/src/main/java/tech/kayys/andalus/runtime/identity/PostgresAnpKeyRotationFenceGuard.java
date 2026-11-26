package tech.kayys.andalus.runtime.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Validates and locks the rotation journal row in the same transaction as a side effect. */
final class PostgresAnpKeyRotationFenceGuard {
    private PostgresAnpKeyRotationFenceGuard() { }

    static void requireCurrent(Connection connection, AnpKeyRotationFence fence) throws SQLException {
        String sql = """
                SELECT state, lease_owner, lease_generation,
                       lease_until > CURRENT_TIMESTAMP AS lease_live
                FROM andalus_anp_key_rotations
                WHERE operation_id = ?
                FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fence.operationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !fence.expectedState().equals(result.getString("state"))
                        || !fence.workerId().equals(result.getString("lease_owner"))
                        || result.getLong("lease_generation") != fence.generation()
                        || !result.getBoolean("lease_live")) {
                    throw new SecurityException("Stale or expired ANP rotation fence; persistence write rejected");
                }
            }
        }
    }
}
