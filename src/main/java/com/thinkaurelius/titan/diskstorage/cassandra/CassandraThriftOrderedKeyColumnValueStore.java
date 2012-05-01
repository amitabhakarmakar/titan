package com.thinkaurelius.titan.diskstorage.cassandra;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnection;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.UncheckedGenericKeyedObjectPool;
import com.thinkaurelius.titan.diskstorage.util.ByteBufferUtil;
import com.thinkaurelius.titan.diskstorage.writeaggregation.*;
import com.thinkaurelius.titan.exceptions.GraphStorageException;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.thrift.Mutation;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class CassandraThriftOrderedKeyColumnValueStore
	implements OrderedKeyColumnValueStore, MultiWriteKeyColumnValueStore {
	
	private final String keyspace;
	private final String columnFamily;
	private final UncheckedGenericKeyedObjectPool<String, CTConnection> pool;
	private final AtomicLong lastMutationTimestamp;
	
	private static final Logger logger =
		LoggerFactory.getLogger(CassandraThriftOrderedKeyColumnValueStore.class);
	
	public CassandraThriftOrderedKeyColumnValueStore(String keyspace, String columnFamily,
                                UncheckedGenericKeyedObjectPool<String, CTConnection> pool) throws RuntimeException {
		this.keyspace = keyspace;
		this.columnFamily = columnFamily;
		this.pool = pool;
		this.lastMutationTimestamp = new AtomicLong(System.currentTimeMillis());
	}

	/**
	 * Call Cassandra's Thrift get_slice() method.
	 * 
	 * When columnEnd equals columnStart, and both startInclusive
	 * and endInclusive are true, then this method calls
	 * {@link #get(java.nio.ByteBuffer, java.nio.ByteBuffer, TransactionHandle)}
	 * instead of calling Thrift's getSlice() method and returns
	 * a one-element list containing the result.
	 * 
	 * When columnEnd equals columnStart and either startInclusive
	 * or endInclusive is false (or both are false), then this
	 * method returns an empty list without making any Thrift calls.
	 * 
	 * If columnEnd = columnStart + 1, and both startInclusive and
	 * startExclusive are false, then the arguments effectively form
	 * an empty interval.  In this case, as in the one previous,
	 * an empty list is returned.  However, it may not necessarily
	 * be handled efficiently; a Thrift call might still be made
	 * before returning the empty list.
	 * 
	 * @throws GraphStorageException when columnEnd < columnStart
	 * 
	 */
	@Override
	public List<Entry> getSlice(ByteBuffer key, ByteBuffer columnStart,
			ByteBuffer columnEnd, boolean startInclusive, boolean endInclusive,
			int limit, TransactionHandle txh) throws GraphStorageException {
		// Sanity check the limit argument
		if (0 > limit) {
			logger.warn("Setting negative limit ({}) to 0", limit);
			limit = 0;
		}
		
		if (0 == limit)
			return ImmutableList.<Entry>of();
		
		ColumnParent parent = new ColumnParent(columnFamily);
		/* 
		 * Cassandra cannot handle columnStart = columnEnd.
		 * Cassandra's Thrift getSlice() throws InvalidRequestException
		 * if columnStart = columnEnd.
		 */
		if (! ByteBufferUtil.isSmallerThan(columnStart, columnEnd)) {
			// Check for invalid arguments where columnEnd < columnStart
			if (ByteBufferUtil.isSmallerThan(columnEnd, columnStart)) {
				throw new GraphStorageException("columnStart=" + columnStart + 
						" is greater than columnEnd=" + columnEnd + ". " +
						"columnStart must be less than or equal to columnEnd");
			}
			/* Must be the case that columnStart equals columnEnd;
			 * check inclusivity and refer to get() if appropriate.
			 */
			if (startInclusive && endInclusive) {
				ByteBuffer name = columnStart.duplicate();
				ByteBuffer value = get(key, columnStart.duplicate(), txh);
				List<Entry> result = new ArrayList<Entry>(1);
				result.add(new Entry(name, value));
				return result;
			} else {
//				logger.debug(
//						"Parameters columnStart=columnEnd={}, " +
//						"startInclusive={}, endInclusive={} " + 
//						"collectively form an empty interval; " +
//						"returning an empty result list.", 
//						new Object[]{columnStart.duplicate(), startInclusive,
//								endInclusive});
				return ImmutableList.<Entry>of();
			}
		}
		
		// true: columnStart < columnEnd
		ConsistencyLevel consistency = getConsistencyLevel();
		SlicePredicate predicate = new SlicePredicate();
		SliceRange range = new SliceRange();
		range.setCount(limit);
		range.setStart(columnStart);
		range.setFinish(columnEnd);
		predicate.setSlice_range(range);
		
		
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			List<ColumnOrSuperColumn> rows = client.get_slice(key, parent, predicate, consistency);
			/*
			 * The final size of the "result" List may be at most rows.size().
			 * However, "result" could also be up to two elements smaller than
			 * rows.size(), depending on startInclusive and endInclusive
			 */
			List<Entry> result = new ArrayList<Entry>(rows.size());
			for (ColumnOrSuperColumn r : rows) {
				Column c = r.getColumn();
				// Skip columnStart if !startInclusive
				if (!startInclusive && ByteBufferUtil.isSmallerOrEqualThan(c.bufferForName(), columnStart))
					continue;
				// Skip columnEnd if !endInclusive
				if (!endInclusive && ByteBufferUtil.isSmallerOrEqualThan(columnEnd, c.bufferForName()))
					continue;
				result.add(new Entry(c.bufferForName(), c.bufferForValue()));
			}
			return result;
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

	@Override
	public List<Entry> getSlice(ByteBuffer key, ByteBuffer columnStart,
			ByteBuffer columnEnd, boolean startInclusive, boolean endInclusive,
			TransactionHandle txh) {
		return getSlice(key, columnStart, columnEnd, 
				startInclusive, endInclusive, Integer.MAX_VALUE, txh); 
	}

	@Override
	public void close() {
		// Do nothing
	}

	@Override
	public boolean containsKey(ByteBuffer key, TransactionHandle txh) {
		ColumnParent parent = new ColumnParent(columnFamily);
		ConsistencyLevel consistency = getConsistencyLevel();
		SlicePredicate predicate = new SlicePredicate();
		SliceRange range = new SliceRange();
		range.setCount(1);
		byte[] empty = new byte[0];
		range.setStart(empty);
		range.setFinish(empty);
		predicate.setSlice_range(range);
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			List<?> result = client.get_slice(key, parent, predicate, consistency);
			return 0 < result.size();
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

    @Override
    public void mutate(ByteBuffer key, List<Entry> additions, List<ByteBuffer> deletions, TransactionHandle txh) {
        if (deletions!=null && !deletions.isEmpty()) delete(key,deletions,txh);
        if (additions!=null && !additions.isEmpty()) insert(key,additions,txh);
    }
    
	public void delete(ByteBuffer key, List<ByteBuffer> columns,
			TransactionHandle txh) {
		ColumnPath path = new ColumnPath(columnFamily);
		long timestamp = getNewTimestamp();
		ConsistencyLevel consistency = getConsistencyLevel();
		
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			for (ByteBuffer col : columns) {
				path.setColumn(col);
				client.remove(key, path, timestamp, consistency);
			}
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

	@Override
	public ByteBuffer get(ByteBuffer key, ByteBuffer column,
			TransactionHandle txh) {
		ColumnPath path = new ColumnPath(columnFamily);
		path.setColumn(column);
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			ColumnOrSuperColumn result =
				client.get(key, path, getConsistencyLevel());
			return result.getColumn().bufferForValue();
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} catch (NotFoundException e) {
			return null;
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

	public void insert(ByteBuffer key, List<Entry> entries,
			TransactionHandle txh) {
		ColumnParent parent = new ColumnParent(columnFamily);
		long timestamp = getNewTimestamp();
		ConsistencyLevel consistency = getConsistencyLevel();
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			for (Entry e : entries) {
				Column column = new Column();
				column.setName(e.getColumn().duplicate());
				column.setValue(e.getValue().duplicate());
				column.setTimestamp(timestamp);
				client.insert(key.duplicate(), parent, column, consistency);
			}
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

	@Override
	public boolean isLocalKey(ByteBuffer key) {
        return true;
	}
	
	private static ConsistencyLevel getConsistencyLevel() {
		return ConsistencyLevel.ALL;
	}
	
	/**
	 * Data-mutating methods call me to obtain a timestamp suitable
	 * for issuing Cassandra writes over Thrift.
	 * <p>
	 * Cassandra resolves write collisions (multiple writes
	 * with identical timestamps) by lexical comparison on value.
	 * Titan sometimes issues a pair of mutations on a given
	 * key-column coordinate in series; if both are issued within
	 * a millisecond, then Cassandra's write collision resolution
	 * can't guarantee that the latter takes precedence (assuming
	 * that the "timestamp" field is just Java currentTimeMillis).
	 * <p>
	 * The return values of this method are guaranteed to strictly
	 * increase.  That is, each value returned by this method will
	 * be greater than the last.  This implementation also returns
	 * values at a rate no faster than one per millisecond, so that
	 * Cassandra timestamps can be kept to actual UNIX Epoch millis.
	 * This method uses an AtomicLong's compare-and-set primitive
	 * for thread safety.
	 * <p>
	 * Note that this method can only ensure strictly increasing
	 * values for method calls on the owning object.  It has no
	 * information about other instances of this class, let alone
	 * other Cassandra clients on remote hosts that could be
	 * issuing colliding writes.  Handling those collisions is out
	 * of scope of this method.
	 * <p>
	 * This method is deliberately heavy-handed with its
	 * backend-wide 1 mutation per ms limit.  There are at least
	 * two ways to relax this limit, either by tracking timers
	 * at the key-column level or by moving from milliseconds in
	 * Cassandra timestamps to something with higher precision.
	 * This implementation shoots for simplicity instead. 
	 * 
	 * @return a timestamp appropriate for use in a Thrift insert,
	 *         delete, etc.
	 */
	private long getNewTimestamp() {
		
		long last, next;
		
		boolean firstTry = true;
		
		do {
			// Insert a random backoff period if we just collided with another writer
			if (!firstTry) {
				try {
					Thread.sleep((long)(Math.random() * 10));
				} catch (InterruptedException e) {
					throw new GraphStorageException("Unexpected interrupt", e);
				}
			}
			
			// Get the current state
			last = lastMutationTimestamp.get();

			// Sleep until the current time is greater than last
			// This is in a loop to guard against spurious Thread.sleep() wakeups
			for (next = System.currentTimeMillis(); next <= last; next = System.currentTimeMillis()) {
				long delta = last - next;
				assert 0 <= delta;
				
				// delta should ideally never exceed 0
				// warn the user if it gets over, say, 50 ms 
				if (50L < delta) {
					logger.warn("Timestamp of last Cassandra mutation " +
							"exceeds current time by {} ms.  This could " +
							"be due to a sudden change to system time.  " +
							"System.currentTimeMillis()={}; " +
							"lastMutationTimestamp={}",
							new Object[] {delta, next, last});
					logger.warn("About to sleep for at least {} ms", delta);
				}
				
				try {
					Thread.sleep(delta + 1L);
				} catch (InterruptedException e) {
					throw new GraphStorageException("Unexpected interrupt", e);
				}
			}
		
			/* This condition is necessary both to fulfill our method
			 * contract and for our CAS protocol to work.
			 */
			assert next > last;
			
			firstTry = false;
		
		// CAS and retry on failure
		} while (! lastMutationTimestamp.compareAndSet(last, next));
		
		return next;
	}

	@Override
	public boolean containsKeyColumn(ByteBuffer key, ByteBuffer column,
			TransactionHandle txh) {
		ColumnParent parent = new ColumnParent(columnFamily);
		ConsistencyLevel consistency = getConsistencyLevel();
		SlicePredicate predicate = new SlicePredicate();
		predicate.setColumn_names(Arrays.asList(column.duplicate()));
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			List<?> result = client.get_slice(key, parent, predicate, consistency);
			return 0 < result.size();
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

	@Override
	public void acquireLock(ByteBuffer key, ByteBuffer column, ByteBuffer expectedValue,
			TransactionHandle txh) {
		// TODO Auto-generated method stub
		
	}

    @Override
    public void mutateMany(Map<ByteBuffer, com.thinkaurelius.titan.diskstorage.writeaggregation.Mutation> mutations, TransactionHandle txh) {
        Map<ByteBuffer, List<Entry>> insertions = new HashMap<ByteBuffer, List<Entry>>(mutations.size());
        Map<ByteBuffer, List<ByteBuffer>> deletions = new HashMap<ByteBuffer, List<ByteBuffer>>(mutations.size());
        for (Map.Entry<ByteBuffer, com.thinkaurelius.titan.diskstorage.writeaggregation.Mutation> entry : mutations.entrySet()) {
            com.thinkaurelius.titan.diskstorage.writeaggregation.Mutation m = entry.getValue();
            ByteBuffer key = entry.getKey();
            if (m.hasAdditions()) insertions.put(key,m.getAdditions());
            if (m.hasDeletions()) deletions.put(key,m.getDeletions());
        }
        deleteMany(deletions,txh);
        insertMany(insertions,txh);
    }

	public void insertMany(Map<ByteBuffer, List<Entry>> insertions,
			TransactionHandle txh) {
		long timestamp = getNewTimestamp();
		
		// Generate Thrift-compatible batch_mutate() datastructure
		Map<ByteBuffer, Map<String, List<Mutation>>> batch =
			new HashMap<ByteBuffer, Map<String, List<Mutation>>>(insertions.size());
		
		for (Map.Entry<ByteBuffer, List<Entry>> ins : insertions.entrySet()) {
			ByteBuffer key = ins.getKey();
			
			List<Mutation> mutationsForCurrentKeyAndCF =
				new ArrayList<Mutation>(ins.getValue().size());
			for (Entry ent : ins.getValue()) {
				Mutation m = new Mutation();
				
				Column thriftCol = new Column();
				thriftCol.setName(ent.getColumn());
				thriftCol.setValue(ent.getValue());
				thriftCol.setTimestamp(timestamp);
				ColumnOrSuperColumn cosc = new ColumnOrSuperColumn();
				cosc.setColumn(thriftCol);
				m.setColumn_or_supercolumn(cosc);

				mutationsForCurrentKeyAndCF.add(m);
			}
			
			batch.put(key, ImmutableMap.of(columnFamily, mutationsForCurrentKeyAndCF));
		}

		batchMutate(batch);
	}

	public void deleteMany(Map<ByteBuffer, List<ByteBuffer>> deletions,
			TransactionHandle txh) {
		long timestamp = getNewTimestamp();
		
		// Generate Thrift-compatible batch_mutate() datastructure
		Map<ByteBuffer, Map<String, List<Mutation>>> batch =
			new HashMap<ByteBuffer, Map<String, List<Mutation>>>(deletions.size());
		
		for (Map.Entry<ByteBuffer, List<ByteBuffer>> ins : deletions.entrySet()) {
			ByteBuffer key = ins.getKey();
			
			List<Mutation> mutationsForCurrentKeyAndCF =
				new ArrayList<Mutation>(ins.getValue().size());
			for (ByteBuffer column : ins.getValue()) {
				Mutation m = new Mutation();
				
				SlicePredicate p = new SlicePredicate();
				p.setColumn_names(ImmutableList.of(column));
				Deletion d = new Deletion();
				d.setTimestamp(timestamp);
				d.setPredicate(p);
				m.setDeletion(d);

				mutationsForCurrentKeyAndCF.add(m);
			}
			
			batch.put(key, ImmutableMap.of(columnFamily, mutationsForCurrentKeyAndCF));
		}

		batchMutate(batch);
	}
	
	private void batchMutate(Map<ByteBuffer, Map<String, List<Mutation>>> batch) {

		ConsistencyLevel consistency = getConsistencyLevel();
		CTConnection conn = null;
		try {
			conn = pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();

			client.batch_mutate(batch, consistency);
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (TimedOutException e) {
			throw new GraphStorageException(e);
		} catch (UnavailableException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
	}

}