package br.com.agregador.auth;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

public final class DatabaseFailures {
  private DatabaseFailures() {}

  public static boolean temporary(Throwable failure) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof DataAccessResourceFailureException) return true;
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        if (state.startsWith("08")
            || state.startsWith("40")
            || state.startsWith("53")
            || state.equals("55P03")
            || state.equals("57014")
            || state.equals("57P01")
            || state.equals("57P02")
            || state.equals("57P03")) return true;
      }
    }
    return false;
  }
}
