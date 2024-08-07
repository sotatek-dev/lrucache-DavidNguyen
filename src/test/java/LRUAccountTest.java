import com.sotatek.model.Account;
import com.sotatek.service.AccountCache;
import com.sotatek.service.impl.AccountCacheImpl;
import com.sotatek.utils.LRUAccountCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;


public class LRUAccountTest {

	private AccountCache accountCache;

	@BeforeEach
	void beforeEachTest() {
		final int cacheCapacity = 4;
		final int topCapacity = 3;
		accountCache = new AccountCacheImpl(cacheCapacity, topCapacity);
	}

	@Test
	void testGetAccountById() {
		Account account1 = new Account(1L, 100L);
		Account account2 = new Account(2L, 200L);

		accountCache.putAccount(account1);
		accountCache.putAccount(account2);

		assertEquals(account1, accountCache.getAccountById(1));
		assertEquals(account2, accountCache.getAccountById(2));
		assertNull(accountCache.getAccountById(3));
		assertEquals(2, accountCache.getAccountByIdHitCount());
	}

	@Test
	void testGetTop3AccountsByBalance() {
		Account account1 = new Account(1L, 100L);
		Account account2 = new Account(2L, 200L);
		Account account3 = new Account(3L, 300L);
		Account account4 = new Account(4L, 400L);

		accountCache.putAccount(account1);
		accountCache.putAccount(account2);
		accountCache.putAccount(account3);
		accountCache.putAccount(account4);

		List<Account> top3 = accountCache.getTop3AccountsByBalance();
		assertEquals(3, top3.size());
		assertEquals(account4, top3.get(0));
		assertEquals(account3, top3.get(1));
		assertEquals(account2, top3.get(2));
	}

	@Nested
	class testEvictionPolicy {
		@Test
		void givenAccount4IsAccessedFirst_whenPutAccount5ToCache_thenAccount4IsRemoved() throws NoSuchFieldException, IllegalAccessException {
			Account account1 = new Account(1L, 100L);
			Account account2 = new Account(2L, 200L);
			Account account3 = new Account(3L, 300L);
			Account account4 = new Account(4L, 400L);
			Account account5 = new Account(5L, 500L);

			accountCache.putAccount(account1);
			accountCache.putAccount(account2);
			accountCache.putAccount(account3);
			accountCache.putAccount(account4);

			// get account4 first, so it will be least recently used
			assertNotNull(accountCache.getAccountById(4));
			assertNotNull(accountCache.getAccountById(1));
			assertNotNull(accountCache.getAccountById(2));
			assertNotNull(accountCache.getAccountById(3));

			accountCache.putAccount(account5);

			assertNull(accountCache.getAccountById(4));
			assertNotNull(accountCache.getAccountById(1));
			assertNotNull(accountCache.getAccountById(2));
			assertNotNull(accountCache.getAccountById(3));
			assertNotNull(accountCache.getAccountById(5));
		}

		@Test
		void givenAccountFrom1To4IsPutInOrderly_whenPutAccount5ToCache_thenAccount1IsRemoved() throws NoSuchFieldException, IllegalAccessException {
			Account account1 = new Account(1L, 100L);
			Account account2 = new Account(2L, 200L);
			Account account3 = new Account(3L, 300L);
			Account account4 = new Account(4L, 400L);
			Account account5 = new Account(5L, 500L);

			// account1 will be least recently used
			accountCache.putAccount(account1);
			accountCache.putAccount(account2);
			accountCache.putAccount(account3);
			accountCache.putAccount(account4);

			// use reflection to get cache field entry set so it's not affect the access order of lru cache implementation
			Map<Long, Account> cache = getCacheField();
			var entries = cache.entrySet();
			assertTrue(entries.stream().anyMatch(e -> e.getKey() == 4L));
			assertTrue(entries.stream().anyMatch(e -> e.getKey() == 3L));
			assertTrue(entries.stream().anyMatch(e -> e.getKey() == 2L));
			assertTrue(entries.stream().anyMatch(e -> e.getKey() == 1L));

			accountCache.putAccount(account5);
			// assert that account 1 is evicted
			assertNull(cache.get(1L));
			assertNotNull(cache.get(2L));
			assertNotNull(cache.get(3L));
			assertNotNull(cache.get(4L));
			assertNotNull(cache.get(5L));
		}

		private Map<Long, Account> getCacheField() throws NoSuchFieldException, IllegalAccessException {
			AccountCacheImpl accountCacheImpl = (AccountCacheImpl) accountCache;
			Field accountCacheField = accountCacheImpl.getClass().getDeclaredField("accountCache");
			accountCacheField.setAccessible(true);
			LRUAccountCache lruAccountCache = (LRUAccountCache) accountCacheField.get(accountCacheImpl);
			// Use reflection to get the cache field from the LRUCache class
			Field cacheField = lruAccountCache.getClass().getSuperclass().getDeclaredField("cache");
			cacheField.setAccessible(true);
			return (Map<Long, Account>) cacheField.get(lruAccountCache);
		}
	}

	@Test
	void testAccountUpdateNotification() {
		Account account1 = new Account(1L, 100L);
		Account account2 = new Account(2L, 200L);

		List<Account> updatedAccounts = new ArrayList<>();
		accountCache.subscribeForAccountUpdates(updatedAccounts::add);

		accountCache.putAccount(account1);
		accountCache.putAccount(account2);

		assertEquals(2, updatedAccounts.size());
		assertTrue(updatedAccounts.contains(account1));
		assertTrue(updatedAccounts.contains(account2));

		account1.setBalance(150L);
		accountCache.putAccount(account1);

		assertEquals(3, updatedAccounts.size());
		assertTrue(updatedAccounts.contains(account1));
	}

	@Test
	void testConcurrentAccess() {
		int numThreads = 10;
		int numAccounts = 4;
		AtomicInteger hits = new AtomicInteger(0);

		List<Thread> threads = new ArrayList<>();
		for (long i = 0; i < numThreads; i++) {
			Thread thread = new Thread(() -> {
				for (long j = 0; j < numAccounts; j++) {
					Account account = new Account(j, j * 100);
					accountCache.putAccount(account);
					accountCache.getAccountById(j);
					hits.incrementAndGet();
				}
			});
			threads.add(thread);
			thread.start();
		}

		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		assertEquals(numThreads * numAccounts, hits.get());
	}
}
