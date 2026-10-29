package br.com.agregador.auth;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.transaction.*;

class DatabaseFailuresTest {
  @Test
  void transactionWrappersKeepTemporaryDatabaseFailuresDistinctFromProgrammingErrors() {
    assertThat(
            DatabaseFailures.temporary(
                new CannotCreateTransactionException(
                    "unavailable", new SQLException("connection", "08001"))))
        .isTrue();
    assertThat(
            DatabaseFailures.temporary(
                new TransactionSystemException(
                    "commit", new SQLException("lost connection", "08006"))))
        .isTrue();
    assertThat(DatabaseFailures.temporary(new QueryTimeoutException("timeout"))).isTrue();
    assertThat(
            DatabaseFailures.temporary(
                new org.springframework.jdbc.BadSqlGrammarException(
                    "query", "invalid", new SQLException("syntax", "42601"))))
        .isFalse();
    assertThat(DatabaseFailures.temporary(new IllegalStateException("bug"))).isFalse();
    assertThat(DatabaseFailures.temporary(new TransactionSystemException("programming failure")))
        .isFalse();
  }
}
