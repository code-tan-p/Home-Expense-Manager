package com.homeexpense.config;

import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Hibernate's SQLite dialect with two corrections, each covered by
 * PersistenceConventionsIntegrationTest.
 *
 * <ol>
 *   <li><b>Schema validation.</b> SQLite stores every integer as a signed 64-bit value
 *   regardless of the declared column type, so INTEGER and BIGINT columns are equivalent.
 *   Auto-incrementing keys must be declared exactly as {@code INTEGER PRIMARY KEY}, which the
 *   driver reports as {@link Types#INTEGER}, while Hibernate expects {@link Types#BIGINT} for
 *   Java {@code long}. Values are still bound and read as {@code long}.</li>
 *   <li><b>Constraint errors.</b> The base dialect does not translate SQLITE_CONSTRAINT, so
 *   CHECK, UNIQUE, FOREIGN KEY, NOT NULL and trigger failures would surface as a generic
 *   JpaSystemException. They are translated to Hibernate's ConstraintViolationException,
 *   which Spring turns into DataIntegrityViolationException.</li>
 * </ol>
 */
public class HomeExpenseSQLiteDialect extends SQLiteDialect {

    private static final int SQLITE_CONSTRAINT = SQLiteErrorCode.SQLITE_CONSTRAINT.code;

    @Override
    public boolean equivalentTypes(int typeCode1, int typeCode2) {
        return super.equivalentTypes(typeCode1, typeCode2)
                || (isSqliteInteger(typeCode1) && isSqliteInteger(typeCode2));
    }

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        SQLExceptionConversionDelegate base = super.buildSQLExceptionConversionDelegate();
        return (sqlException, message, sql) -> {
            if (sqlException.getErrorCode() == SQLITE_CONSTRAINT) {
                return new ConstraintViolationException(message, sqlException, sql, constraintKind(sqlException), null);
            }
            return base == null ? null : base.convert(sqlException, message, sql);
        };
    }

    private static boolean isSqliteInteger(int typeCode) {
        return typeCode == Types.INTEGER || typeCode == Types.BIGINT;
    }

    private static ConstraintKind constraintKind(SQLException sqlException) {
        if (!(sqlException instanceof SQLiteException sqliteException)) {
            return ConstraintKind.OTHER;
        }
        return switch (sqliteException.getResultCode()) {
            case SQLITE_CONSTRAINT_UNIQUE, SQLITE_CONSTRAINT_PRIMARYKEY -> ConstraintKind.UNIQUE;
            case SQLITE_CONSTRAINT_FOREIGNKEY -> ConstraintKind.FOREIGN_KEY;
            case SQLITE_CONSTRAINT_CHECK -> ConstraintKind.CHECK;
            case SQLITE_CONSTRAINT_NOTNULL -> ConstraintKind.NOT_NULL;
            default -> ConstraintKind.OTHER;
        };
    }
}
