package br.com.agregador.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

class DatabaseFailuresTest {
  @Test
  void poolAcquisitionWithoutSqlStateAndTransactionTimeoutRemainTemporary() {
    var unavailable = new SQLTransientConnectionException("pool exhausted");
    assertThat(unavailable.getSQLState()).isNull();
    assertThat(DatabaseFailures.temporary(unavailable)).isTrue();
    assertThat(
            DatabaseFailures.temporary(
                new CannotCreateTransactionException("acquire", unavailable)))
        .isTrue();
    assertThat(DatabaseFailures.temporary(new TransactionTimedOutException("deadline"))).isTrue();
    assertThat(
            DatabaseFailures.temporary(
                new TransactionSystemException(
                    "wrapped timeout", new TransactionTimedOutException("deadline"))))
        .isTrue();
    assertThat(DatabaseFailures.temporary(new SQLException("unspecified failure"))).isFalse();
  }

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
