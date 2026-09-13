package com.example.walletledger.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.messaging.kafka.BalanceProjection;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scratch audit only: assertions document a defect; do not retain as desired-behavior tests. */
class F03ProjectionOverflowCharacterizationIT extends PostgresIntegrationTest {
  @Autowired BalanceProjection projection;
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate sql;

  @Test
  void oversizedNumbersWrapAndFreezeProjectionWhileAuthoritativeWalletRemainsCorrect() {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    wallets.credit(player, 100, "qa", "initial funds", "qa-f03", UUID.randomUUID().toString());
    projection.accept(event(player, "1", "100", "1"));
    // 3*2^63-1 narrows to Long.MAX_VALUE; 2^64 narrows to zero.
    projection.accept(event(player, "27670116110564327423", "18446744073709551616", "1"));
    assertThat(sql.queryForMap(
        "select wallet_sequence,balance from wallet_projection where wallet_id=?", player))
        .containsEntry("wallet_sequence", Long.MAX_VALUE).containsEntry("balance", 0L);
    wallets.debit(player, 30, "qa", "real purchase", "qa-f03", UUID.randomUUID().toString());
    projection.accept(event(player, "2", "70", "1"));
    assertThat(sql.queryForMap(
        "select wallet_sequence,balance from wallet_projection where wallet_id=?", player))
        .containsEntry("wallet_sequence", Long.MAX_VALUE).containsEntry("balance", 0L);
    assertThat(wallets.balance(player)).containsEntry("balance", 70L).containsEntry("sequence", 2L);
    assertThat(sql.queryForObject("select count(*) from consumed_event where wallet_id=?",
        Long.class, player)).isEqualTo(3);
    assertThat(administratorJdbc().queryForList("select * from public.audit_ledger_integrity()"))
        .isEmpty();
    System.out.println("F03 expected-defect: projection=(9223372036854775807,0), "
        + "authoritative wallet=(2,70), consumedEvents=3, ledger audit=clean");
  }

  @Test
  void nonIntegralAndWrongTypedSchemaVersionsAreCoercedToVersionOne() {
    for (String version : new String[] {"1.5", "\"1\"", "true", "4294967297"}) {
      UUID wallet = UUID.randomUUID();
      projection.accept(event(wallet, "1", "10", version));
      assertThat(sql.queryForObject("select balance from wallet_projection where wallet_id=?",
          Long.class, wallet)).isEqualTo(10);
      System.out.println("F03 expected-defect: accepted schemaVersion=" + version);
    }
  }

  private String event(UUID wallet, String sequence, String balance, String version) {
    return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"walletId\":\"" + wallet
        + "\",\"walletSequence\":" + sequence + ",\"balanceAfter\":" + balance
        + ",\"schemaVersion\":" + version + "}";
  }
}
