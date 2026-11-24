/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

/**
 * A role with CREATE on a schema of the application's search_path must not get its own operators,
 * functions or types used by the catalog queries of DatabaseMetaData, ResultSetMetaData and
 * TypeInfoCache. Every object the "attacker" creates here fails when the backend runs it.
 */
public class CatalogSearchPathHijackTest {
  private static final String EVIL = "catalog_hijack_evil";
  private static final String VICTIM = "catalog_hijack_victim";

  private Connection con;
  private int hijackCount;

  @BeforeEach
  public void setUp() throws SQLException {
    con = TestUtil.openDB();
    TestUtil.createSchema(con, EVIL);
    TestUtil.createSchema(con, VICTIM);
    TestUtil.execute(con, "CREATE TABLE " + VICTIM + ".target"
        + " (id int4 PRIMARY KEY, iv interval, addr inet)");
    TestUtil.execute(con, "CREATE FUNCTION " + VICTIM + ".target_rows() RETURNS SETOF "
        + VICTIM + ".target AS 'SELECT * FROM " + VICTIM + ".target' LANGUAGE sql");
    TestUtil.execute(con, "CREATE DOMAIN " + VICTIM + ".target_dom AS int4");
  }

  @AfterEach
  public void tearDown() throws SQLException {
    try {
      TestUtil.dropSchema(con, EVIL);
      TestUtil.dropSchema(con, VICTIM);
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * Creates {@code EVIL.op(left, right)}, an exact match for the argument types, backed by a
   * function that raises an error unless {@code guard} returns first.
   */
  private void createHijackOperator(String op, String left, String right, String returns,
      String guard) throws SQLException {
    String fn = EVIL + ".hijack_" + (++hijackCount);
    TestUtil.execute(con, "CREATE FUNCTION " + fn + "(" + left + ", " + right + ") RETURNS "
        + returns + " AS $$ BEGIN " + guard
        + " RAISE EXCEPTION 'search_path hijack: operator " + op + "(" + left + ", " + right
        + ")'; END $$ LANGUAGE plpgsql");
    TestUtil.execute(con, "CREATE OPERATOR " + EVIL + "." + op + " (LEFTARG = " + left
        + ", RIGHTARG = " + right + ", PROCEDURE = " + fn + ")");
  }

  private void createHijackFunction(String name, String args) throws SQLException {
    TestUtil.execute(con, "CREATE FUNCTION " + EVIL + "." + name + "(" + args
        + ") RETURNS text AS $$ BEGIN RAISE EXCEPTION 'search_path hijack: function " + name
        + "(" + args + ")'; END $$ LANGUAGE plpgsql");
  }

  /**
   * The operators and functions that an unqualified name in the catalog queries resolves to when
   * they are on the search_path: pg_catalog has no exact match for these argument types.
   */
  private void createHijackOperators() throws SQLException {
    // name LIKE 'pattern', name ~ 'regex', name !~ 'regex': unknown is taken as name
    createHijackOperator("~~", "name", "name", "boolean", "");
    createHijackOperator("~", "name", "name", "boolean", "");
    createHijackOperator("!~", "name", "name", "boolean", "");
    // p.proname || '_' || p.oid
    createHijackOperator("||", "name", "name", "text", "");
    // t.typrelid = 0, a.attrelid = <relid>, base_type.oid = <oid>, c.oid = vals.oid,
    // t.oid = ? with setInt.
    // PostgreSQL before 14 compares relnamespace = 11 unqualified in pg_catalog.obj_description
    // itself, so that one call is answered here.
    createHijackOperator("=", "oid", "integer", "boolean",
        "IF $2 OPERATOR(pg_catalog.=) 11 THEN RETURN $1 OPERATOR(pg_catalog.=) $2::pg_catalog.oid;"
            + " END IF;");
    // pg_type.oid = ? with setLong
    createHijackOperator("=", "oid", "bigint", "boolean", "");
    // typinput = 'pg_catalog.array_in'::regproc
    createHijackOperator("=", "regproc", "regproc", "boolean", "");
    // d.classoid = 'pg_class'::regclass
    createHijackOperator("=", "oid", "regclass", "boolean", "");
    // typname = ? and nspname = ? with setString
    createHijackOperator("=", "name", "varchar", "boolean", "");
    // format_type(p.prorettype, null): the unknown null prefers the string category
    createHijackFunction("format_type", "oid, text");
    // replace(name, 'pg_temp_', 'pg_toast_temp_'): name is an exact match for the first argument
    createHijackFunction("replace", "name, text, text");
  }

  private Connection openVictim() throws SQLException {
    Connection victim = TestUtil.openDB();
    // A temporary table with a toastable column creates the pg_temp_N and pg_toast_temp_N schemas
    TestUtil.execute(victim, "CREATE TEMP TABLE catalog_hijack_tmp (t text)");
    TestUtil.execute(victim, "SET search_path TO public, " + EVIL);
    return victim;
  }

  private static Set<String> column(ResultSet rs, String column) throws SQLException {
    Set<String> values = new HashSet<String>();
    try {
      while (rs.next()) {
        values.add(rs.getString(column));
      }
    } finally {
      rs.close();
    }
    return values;
  }

  private int queryInt(String sql) throws SQLException {
    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(sql);
      assertTrue(rs.next(), sql);
      return (int) rs.getLong(1);
    } finally {
      st.close();
    }
  }

  @Test
  public void databaseMetaDataIgnoresOperatorsOnSearchPath() throws SQLException {
    createHijackOperators();
    Connection victim = openVictim();
    try {
      DatabaseMetaData md = victim.getMetaData();

      assertTrue(column(md.getTables(null, VICTIM, "targ%", new String[]{"TABLE",
          "PARTITIONED TABLE", "INDEX", "PARTITIONED INDEX", "TYPE", "TEMPORARY TABLE",
          "TEMPORARY INDEX", "TEMPORARY VIEW", "TEMPORARY SEQUENCE"}), "TABLE_NAME")
          .contains("target"), "getTables");
      assertTrue(column(md.getTables(null, null, "catalog_hijack_tmp",
          new String[]{"TEMPORARY TABLE"}), "TABLE_NAME").contains("catalog_hijack_tmp"),
          "getTables TEMPORARY TABLE");
      Set<String> schemas = column(md.getSchemas(null, "catalog_hijack%"), "TABLE_SCHEM");
      assertTrue(schemas.contains(EVIL) && schemas.contains(VICTIM), "getSchemas " + schemas);
      assertEquals(3, column(md.getColumns(null, VICTIM, "target", "%"), "COLUMN_NAME").size(),
          "getColumns");
      column(md.getColumnPrivileges(null, VICTIM, "target", "%"), "COLUMN_NAME");
      column(md.getTablePrivileges(null, VICTIM, "targ%"), "TABLE_NAME");
      column(md.getProcedures(null, VICTIM, "target%"), "PROCEDURE_NAME");
      assertTrue(column(md.getProcedureColumns(null, VICTIM, "target_rows", null), "COLUMN_NAME")
          .contains("addr"), "getProcedureColumns");
      assertTrue(column(md.getFunctions(null, VICTIM, "target%"), "FUNCTION_NAME")
          .contains("target_rows"), "getFunctions");
      assertTrue(column(md.getFunctionColumns(null, VICTIM, "target_rows", null), "COLUMN_NAME")
          .contains("addr"), "getFunctionColumns");
      assertTrue(column(md.getTypeInfo(), "TYPE_NAME").contains("int4"), "getTypeInfo");
      assertTrue(column(md.getUDTs(null, VICTIM, "target%", null), "TYPE_NAME")
          .contains("target_dom"), "getUDTs");
    } finally {
      TestUtil.closeDB(victim);
    }
  }

  @Test
  public void resultSetMetaDataIgnoresOperatorsOnSearchPath() throws SQLException {
    createHijackOperators();
    Connection victim = openVictim();
    try {
      Statement st = victim.createStatement();
      ResultSet rs = st.executeQuery("SELECT id FROM " + VICTIM + ".target");
      ResultSetMetaData rsmd = rs.getMetaData();
      assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(1), "isNullable");
      assertFalse(rsmd.isAutoIncrement(1), "isAutoIncrement");
      rs.close();
      st.close();
    } finally {
      TestUtil.closeDB(victim);
    }
  }

  @Test
  public void typeInfoCacheIgnoresOperatorsOnSearchPath() throws SQLException {
    int rowType = queryInt("SELECT '" + VICTIM + ".target'::regtype::oid");
    int arrayType = queryInt("SELECT '" + VICTIM + ".target[]'::regtype::oid");
    int domain = queryInt("SELECT '" + VICTIM + ".target_dom'::regtype::oid");
    createHijackOperators();
    Connection victim = openVictim();
    try {
      TypeInfo typeInfo = victim.unwrap(BaseConnection.class).getTypeInfo();
      assertEquals(Oid.INET, typeInfo.getPGType("inet"), "getPGType(inet)");
      assertEquals(rowType, typeInfo.getPGType(VICTIM + ".target"), "getPGType(target)");
      assertEquals(arrayType, typeInfo.getPGType(VICTIM + ".target[]"), "getPGType(target[])");
      assertEquals("\"" + VICTIM + "\".\"target_dom\"", typeInfo.getPGType(domain),
          "getPGType(oid)");
      assertEquals(rowType, typeInfo.getPGArrayElement(arrayType), "getPGArrayElement");
      assertEquals(',', typeInfo.getArrayDelimiter(arrayType), "getArrayDelimiter");
      assertEquals(Types.ARRAY, typeInfo.getSQLType(arrayType), "getSQLType(array)");
      assertEquals(Types.DISTINCT, typeInfo.getSQLType(domain), "getSQLType(domain)");
    } finally {
      TestUtil.closeDB(victim);
    }
  }

  private void createIntervalDomain(String schema) throws SQLException {
    TestUtil.execute(con, "CREATE FUNCTION " + EVIL + ".hijack_check(interval) RETURNS boolean"
        + " AS $$ BEGIN RAISE EXCEPTION 'search_path hijack: domain check'; END $$"
        + " LANGUAGE plpgsql");
    TestUtil.execute(con, "CREATE DOMAIN " + schema + ".interval AS interval CHECK ("
        + EVIL + ".hijack_check(VALUE))");
  }

  /**
   * A PGInterval parameter must be sent as pg_catalog.interval, so no CHECK of a domain named
   * interval runs in the session.
   */
  private static void assertIntervalIsBuiltin(Connection victim) throws SQLException {
    PreparedStatement ps = victim.prepareStatement("SELECT ?");
    try {
      ps.setObject(1, new PGInterval("1 day"));
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next());
      rs.close();
    } finally {
      ps.close();
    }
    TypeInfo typeInfo = victim.unwrap(BaseConnection.class).getTypeInfo();
    assertEquals(Oid.INTERVAL, typeInfo.getPGType("interval"), "getPGType(interval)");
    assertEquals(Oid.INTERVAL_ARRAY, typeInfo.getPGType("interval[]"), "getPGType(interval[])");
    assertEquals("interval", typeInfo.getPGType(Oid.INTERVAL), "getPGType(1186)");
  }

  @Test
  public void intervalDomainOnSearchPathIsNotUsed() throws SQLException {
    createIntervalDomain(EVIL);
    Connection victim = TestUtil.openDB();
    try {
      TestUtil.execute(victim, "SET search_path TO public, " + EVIL);
      assertIntervalIsBuiltin(victim);
    } finally {
      TestUtil.closeDB(victim);
    }
  }

  @Test
  public void intervalDomainOutsideSearchPathIsNotUsed() throws SQLException {
    createIntervalDomain(EVIL);
    Connection victim = TestUtil.openDB();
    try {
      assertIntervalIsBuiltin(victim);
    } finally {
      TestUtil.closeDB(victim);
    }
  }

  @Test
  public void intervalDomainInTemporarySchemaOfOtherSessionIsNotUsed() throws SQLException {
    Connection attacker = TestUtil.openDB();
    try {
      // the domain lives in the temporary schema of the attacker's session
      TestUtil.execute(con, "CREATE FUNCTION " + EVIL + ".hijack_check(interval) RETURNS boolean"
          + " AS $$ BEGIN RAISE EXCEPTION 'search_path hijack: domain check'; END $$"
          + " LANGUAGE plpgsql");
      TestUtil.execute(attacker, "CREATE DOMAIN pg_temp.interval AS interval CHECK ("
          + EVIL + ".hijack_check(VALUE))");
      Connection victim = TestUtil.openDB();
      try {
        assertIntervalIsBuiltin(victim);
      } finally {
        TestUtil.closeDB(victim);
      }
      TestUtil.execute(attacker, "DROP DOMAIN pg_temp.interval");
    } finally {
      TestUtil.closeDB(attacker);
    }
  }
}
