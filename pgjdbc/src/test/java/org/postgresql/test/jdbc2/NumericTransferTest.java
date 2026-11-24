/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class NumericTransferTest extends BaseTest4 {
  public NumericTransferTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.NUMERIC);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Test
  public void receive100000() throws SQLException {
    Statement statement = con.createStatement();
    for (String sign : new String[]{"", "-"}) {
      for (int i = 0; i < 100; i++) {
        final String sql = "SELECT " + sign + "1E+" + i + "::numeric";
        ResultSet rs = statement.executeQuery(sql);
        rs.next();
        if (i == 0) {
          final String expected = sign + "1";
          assertEquals("getString for " + sql, expected, rs.getString(1));
          assertEquals("getBigDecimal for " + sql, expected, rs.getBigDecimal(1).toString());
        } else {
          final String expected = sign + String.format("1%0" + i + "d", 0);
          assertEquals("getString for " + sql, expected, rs.getString(1));
          assertEquals("getBigDecimal for " + sql, expected, rs.getBigDecimal(1).toString());
        }
        rs.close();
      }
    }
    statement.close();
  }

  @Test
  public void sendReceive100000() throws SQLException {
    PreparedStatement statement = con.prepareStatement("select ?::numeric");
    for (String sign : new String[]{"", "-"}) {
      for (int i = 0; i < 100; i++) {
        final String expected = sign + (i == 0 ? 1 : String.format("1%0" + i + "d", 0));
        statement.setBigDecimal(1, new BigDecimal(expected));
        ResultSet rs = statement.executeQuery();
        rs.next();
        assertEquals("getString for " + expected, expected, rs.getString(1));
        assertEquals("getBigDecimal for " + expected, expected, rs.getBigDecimal(1).toString());
        rs.close();
      }
    }
    statement.close();
  }

  @Test
  public void sendScaleOutOfRangeIsNotTruncated() throws SQLException {
    // The binary format keeps 14 bits of the scale, so the value used to be stored as 999999.99
    assertNotSentAs(new BigDecimal("999999.99e-262144"), new BigDecimal("999999.99"));
  }

  @Test
  public void sendWeightOutOfRangeIsNotTruncated() throws SQLException {
    // The binary format keeps 16 bits of the weight, so the value used to be stored as 0
    assertNotSentAs(new BigDecimal(BigInteger.ONE, -200000), BigDecimal.ZERO);
  }

  @Test(timeout = 30000)
  public void sendHugeScaleFailsFast() throws SQLException {
    // 10^scale used to be computed before sending, which keeps the thread busy for minutes
    assertNotSentAs(new BigDecimal("1e-200000000"), BigDecimal.ZERO);
  }

  private void assertNotSentAs(BigDecimal value, BigDecimal truncated) throws SQLException {
    PreparedStatement statement = con.prepareStatement("select ?::numeric");
    try {
      statement.setBigDecimal(1, value);
      ResultSet rs;
      try {
        rs = statement.executeQuery();
      } catch (SQLException e) {
        // the value does not fit numeric, so the server rejects it
        return;
      }
      assertTrue(rs.next());
      BigDecimal actual = rs.getBigDecimal(1);
      rs.close();
      assertTrue("setBigDecimal(" + value + ") must not be stored as " + truncated,
          actual == null || actual.compareTo(truncated) != 0);
    } finally {
      statement.close();
    }
  }
}
