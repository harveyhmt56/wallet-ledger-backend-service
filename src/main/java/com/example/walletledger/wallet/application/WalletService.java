package com.example.walletledger.wallet.application;

import com.example.walletledger.wallet.domain.BusinessException;
import com.example.walletledger.wallet.domain.MoneyRules;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The single posting boundary shared by wallet, reward and cancellation use cases. */
@Service
@Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
public class WalletService {
  private static final UUID ISSUANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PURCHASE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  public WalletService(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Map<String, Object> provision(UUID playerId) {
    required(playerId, "playerId");
    lockBusiness("player:" + playerId);
    if (jdbc.sql("select count(*) from player where player_id=:id")
            .param("id", playerId)
            .query(Long.class)
            .single()
        > 0) {
      throw conflict("PLAYER_EXISTS", "Player already exists");
    }
    UUID accountId = UUID.randomUUID();
    jdbc.sql("insert into player(player_id) values (:id)").param("id", playerId).update();
    jdbc.sql(
            "insert into ledger_account(account_id,player_id,kind) values (:account,:player,'PLAYER')")
        .param("account", accountId)
        .param("player", playerId)
        .update();
    jdbc.sql("insert into wallet(wallet_id,player_id,account_id) values (:id,:id,:account)")
        .param("id", playerId)
        .param("account", accountId)
        .update();
    return Map.of("playerId", playerId, "walletId", playerId, "balance", 0L, "sequence", 0L);
  }

  public Map<String, Object> credit(
      UUID playerId, long amount, String actor, String reason, String source, String reference) {
    metadata(actor, reason, source, reference);
    lockReference("CREDIT", source, reference);
    Wallet wallet = lockWallets(playerId).getFirst();
    long balance = MoneyRules.credit(wallet.balance(), amount);
    return post(
        "CREDIT",
        amount,
        actor,
        reason,
        source,
        reference,
        null,
        List.of(
            playerPosting(wallet, amount, balance), new Posting(ISSUANCE, null, -amount, 0, 0)));
  }

  public Map<String, Object> debit(
      UUID playerId, long amount, String actor, String reason, String source, String reference) {
    metadata(actor, reason, source, reference);
    lockReference("DEBIT", source, reference);
    Wallet wallet = lockWallets(playerId).getFirst();
    long balance = MoneyRules.debit(wallet.balance(), amount);
    return post(
        "DEBIT",
        amount,
        actor,
        reason,
        source,
        reference,
        null,
        List.of(
            playerPosting(wallet, -amount, balance), new Posting(PURCHASE, null, amount, 0, 0)));
  }

  public Map<String, Object> transfer(
      UUID sender,
      UUID recipient,
      long amount,
      String actor,
      String reason,
      String source,
      String reference) {
    required(sender, "sender");
    required(recipient, "recipient");
    if (sender.equals(recipient))
      throw new BusinessException(400, "SELF_TRANSFER", "Sender and recipient must differ");
    metadata(actor, reason, source, reference);
    lockReference("TRANSFER", source, reference);
    List<Wallet> locked = lockWallets(sender, recipient);
    Wallet from = locked.stream().filter(w -> w.id().equals(sender)).findFirst().orElseThrow();
    Wallet to = locked.stream().filter(w -> w.id().equals(recipient)).findFirst().orElseThrow();
    long fromBalance = MoneyRules.debit(from.balance(), amount);
    long toBalance = MoneyRules.credit(to.balance(), amount);
    Map<String, Object> receipt =
        post(
            "TRANSFER",
            amount,
            actor,
            reason,
            source,
            reference,
            null,
            List.of(
                playerPosting(from, -amount, fromBalance), playerPosting(to, amount, toBalance)));
    receipt.put("recipientId", recipient);
    receipt.put("recipientBalanceAfter", toBalance);
    return receipt;
  }

  public Map<String, Object> refund(
      UUID transactionId, String actor, String reason, String source, String reference) {
    required(transactionId, "transactionId");
    metadata(actor, reason, source, reference);
    // Immutable journal rows need no UPDATE privilege: an original-specific advisory lock
    // serializes cancellation.
    lockBusiness("refund:" + transactionId);
    Original original =
        jdbc.sql("select operation,amount from journal_transaction where transaction_id=:id")
            .param("id", transactionId)
            .query((rs, n) -> new Original(rs.getString(1), rs.getLong(2)))
            .optional()
            .orElseThrow(() -> missing("TRANSACTION_NOT_FOUND", "Transaction does not exist"));
    if (!Set.of("CREDIT", "DEBIT").contains(original.operation())) {
      throw conflict("REFUND_NOT_SUPPORTED", "Only full credit or debit reversals are supported");
    }
    if (jdbc.sql("select count(*) from journal_transaction where original_transaction_id=:id")
            .param("id", transactionId)
            .query(Long.class)
            .single()
        > 0) {
      throw conflict("ALREADY_REFUNDED", "Transaction has already been reversed");
    }
    lockReference("REFUND", source, reference);
    List<OriginalEntry> entries =
        jdbc.sql(
                "select account_id,wallet_id,amount from ledger_entry where transaction_id=:id order by wallet_id nulls last")
            .param("id", transactionId)
            .query(
                (rs, n) ->
                    new OriginalEntry(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)))
            .list();
    UUID player =
        entries.stream()
            .map(OriginalEntry::walletId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow();
    Wallet wallet = lockWallets(player).getFirst();
    List<Posting> postings = new ArrayList<>();
    for (OriginalEntry entry : entries) {
      long delta = -entry.amount();
      if (entry.walletId() == null) postings.add(new Posting(entry.accountId(), null, delta, 0, 0));
      else
        postings.add(
            playerPosting(
                wallet,
                delta,
                delta > 0
                    ? MoneyRules.credit(wallet.balance(), delta)
                    : MoneyRules.debit(wallet.balance(), -delta)));
    }
    Map<String, Object> receipt =
        post(
            "REFUND", original.amount(), actor, reason, source, reference, transactionId, postings);
    receipt.put("originalTransactionId", transactionId);
    return receipt;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> balance(UUID playerId) {
    required(playerId, "playerId");
    Wallet wallet =
        jdbc.sql("select wallet_id,account_id,balance,sequence from wallet where wallet_id=:id")
            .param("id", playerId)
            .query(
                (rs, n) ->
                    new Wallet(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getLong(3),
                        rs.getLong(4)))
            .optional()
            .orElseThrow(() -> missing("PLAYER_NOT_FOUND", "Player does not exist"));
    return Map.of(
        "playerId",
        playerId,
        "walletId",
        wallet.id(),
        "balance",
        wallet.balance(),
        "sequence",
        wallet.sequence());
  }

  @Transactional(readOnly = true)
  public Map<String, Object> history(UUID playerId, Long cursor, int limit) {
    balance(playerId);
    if (limit < 1 || limit > 100 || (cursor != null && cursor < 1)) {
      throw new BusinessException(
          400, "INVALID_PAGINATION", "Limit must be 1 to 100 and cursor must be positive");
    }
    String cursorClause = cursor == null ? "" : " and e.wallet_sequence < :cursor";
    var query =
        jdbc.sql(
                """
            select j.transaction_id,j.operation,j.reason,j.source,j.reference,j.actor,j.created_at,
                   e.amount,e.balance_after,e.wallet_sequence
            from ledger_entry e join journal_transaction j on j.transaction_id=e.transaction_id
            where e.wallet_id=:id
            """
                    + cursorClause
                    + " order by e.wallet_sequence desc limit :limit")
            .param("id", playerId)
            .param("limit", limit + 1);
    if (cursor != null) query = query.param("cursor", cursor);
    List<Map<String, Object>> rows =
        query
            .query(
                (rs, n) -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("transactionId", rs.getObject("transaction_id", UUID.class));
                  row.put("operation", rs.getString("operation"));
                  row.put("reason", rs.getString("reason"));
                  row.put("source", rs.getString("source"));
                  row.put("reference", rs.getString("reference"));
                  row.put("actor", rs.getString("actor"));
                  row.put(
                      "occurredAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                  row.put("delta", rs.getLong("amount"));
                  row.put("balanceAfter", rs.getLong("balance_after"));
                  row.put("walletSequence", rs.getLong("wallet_sequence"));
                  return row;
                })
            .list();
    boolean more = rows.size() > limit;
    List<Map<String, Object>> page = more ? rows.subList(0, limit) : rows;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", page);
    result.put("nextCursor", more ? page.getLast().get("walletSequence") : null);
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> reconciliation() {
    List<Map<String, Object>> mismatches =
        jdbc.sql(
                """
            select w.wallet_id,w.balance,coalesce(sum(e.amount::numeric),0) ledger_balance
            from wallet w left join ledger_entry e on e.account_id=w.account_id
            group by w.wallet_id,w.balance having w.balance::numeric <> coalesce(sum(e.amount::numeric),0)
            """)
            .query(
                (rs, n) ->
                    Map.<String, Object>of(
                        "walletId",
                        rs.getObject(1, UUID.class),
                        "balance",
                        rs.getLong(2),
                        "ledgerBalance",
                        rs.getBigDecimal(3)))
            .list();
    return Map.of("consistent", mismatches.isEmpty(), "mismatches", mismatches);
  }

  private Map<String, Object> post(
      String operation,
      long amount,
      String actor,
      String reason,
      String source,
      String reference,
      UUID original,
      List<Posting> postings) {
    UUID transactionId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.sql(
            """
            insert into journal_transaction(transaction_id,operation,amount,actor,reason,source,reference,original_transaction_id,created_at)
            values (:id,:operation,:amount,:actor,:reason,:source,:reference,:original,:created)
            """)
        .param("id", transactionId)
        .param("operation", operation)
        .param("amount", amount)
        .param("actor", actor)
        .param("reason", reason)
        .param("source", source)
        .param("reference", reference)
        .param("original", original, Types.OTHER)
        .param("created", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .update();
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("transactionId", transactionId);
    receipt.put("operation", operation);
    receipt.put("amount", amount);
    receipt.put("reason", reason);
    receipt.put("occurredAt", now);
    for (Posting posting : postings) {
      jdbc.sql(
              """
                insert into ledger_entry(entry_id,transaction_id,account_id,amount,wallet_id,wallet_sequence,balance_after)
                values (:id,:transaction,:account,:amount,:wallet,:sequence,:balance)
                """)
          .param("id", UUID.randomUUID())
          .param("transaction", transactionId)
          .param("account", posting.accountId())
          .param("amount", posting.delta())
          .param("wallet", posting.walletId(), Types.OTHER)
          .param("sequence", posting.walletId() == null ? null : posting.sequence(), Types.BIGINT)
          .param("balance", posting.walletId() == null ? null : posting.balance(), Types.BIGINT)
          .update();
      if (posting.walletId() != null) {
        jdbc.sql("update wallet set balance=:balance,sequence=:sequence where wallet_id=:id")
            .param("balance", posting.balance())
            .param("sequence", posting.sequence())
            .param("id", posting.walletId())
            .update();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> event =
            Map.of(
                "eventId",
                eventId,
                "walletId",
                posting.walletId(),
                "walletSequence",
                posting.sequence(),
                "journalTransactionId",
                transactionId,
                "delta",
                posting.delta(),
                "balanceAfter",
                posting.balance(),
                "reason",
                reason,
                "occurredAt",
                now,
                "schemaVersion",
                1);
        jdbc.sql(
                """
                    insert into outbox_event(event_id,wallet_id,wallet_sequence,journal_transaction_id,payload,created_at)
                    values (:id,:wallet,:sequence,:transaction,cast(:payload as jsonb),:created)
                    """)
            .param("id", eventId)
            .param("wallet", posting.walletId())
            .param("sequence", posting.sequence())
            .param("transaction", transactionId)
            .param("payload", json(event))
            .param("created", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .update();
        if (!receipt.containsKey("walletId")) {
          receipt.put("playerId", posting.walletId());
          receipt.put("walletId", posting.walletId());
          receipt.put("balanceAfter", posting.balance());
          receipt.put("walletSequence", posting.sequence());
        }
      }
    }
    return receipt;
  }

  private Posting playerPosting(Wallet wallet, long delta, long balance) {
    return new Posting(
        wallet.accountId(),
        wallet.id(),
        delta,
        balance,
        MoneyRules.nextSequence(wallet.sequence()));
  }

  private List<Wallet> lockWallets(UUID... ids) {
    List<Wallet> result = new ArrayList<>();
    // UUID textual order matches PostgreSQL's unsigned byte order, unlike UUID.compareTo.
    List<UUID> sorted =
        Arrays.stream(ids)
            .peek(id -> required(id, "playerId"))
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    for (UUID id : sorted) {
      Wallet wallet =
          jdbc.sql(
                  """
                select w.wallet_id,w.account_id,w.balance,w.sequence,p.status
                from wallet w join player p on p.player_id=w.player_id where w.wallet_id=:id for update of w
                """)
              .param("id", id)
              .query(
                  (rs, n) -> {
                    if (!"ACTIVE".equals(rs.getString(5)))
                      throw conflict("PLAYER_SUSPENDED", "Player is suspended");
                    return new Wallet(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getLong(3),
                        rs.getLong(4));
                  })
              .optional()
              .orElseThrow(() -> missing("PLAYER_NOT_FOUND", "Player does not exist"));
      result.add(wallet);
    }
    return result;
  }

  private void lockReference(String operation, String source, String reference) {
    lockBusiness("reference:" + operation + ":" + source + ":" + reference);
    if (jdbc.sql(
                "select count(*) from journal_transaction where operation=:operation and source=:source and reference=:reference")
            .param("operation", operation)
            .param("source", source)
            .param("reference", reference)
            .query(Long.class)
            .single()
        > 0) {
      throw conflict(
          "BUSINESS_REFERENCE_USED", "This business reference already has a journal transaction");
    }
  }

  private void lockBusiness(String identity) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:identity,0))")
        .param("identity", identity)
        .query((rs, n) -> 0)
        .single();
  }

  private void metadata(String actor, String reason, String source, String reference) {
    text(actor, 200, "actor");
    text(reason, 500, "reason");
    text(source, 100, "source");
    text(reference, 200, "reference");
  }

  private void text(String value, int max, String name) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new BusinessException(
          400, "INVALID_INPUT", name + " must be nonblank and at most " + max + " characters");
  }

  private void required(Object value, String name) {
    if (value == null) throw new BusinessException(400, "INVALID_INPUT", name + " is required");
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize wallet event", e);
    }
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(409, code, message);
  }

  private BusinessException missing(String code, String message) {
    return new BusinessException(404, code, message);
  }

  private record Wallet(UUID id, UUID accountId, long balance, long sequence) {}

  private record Posting(UUID accountId, UUID walletId, long delta, long balance, long sequence) {}

  private record Original(String operation, long amount) {}

  private record OriginalEntry(UUID accountId, UUID walletId, long amount) {}
}
