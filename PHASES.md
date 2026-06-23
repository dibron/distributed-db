# Distributed Database — Learning Plan

> You're building a database from scratch to understand how real databases
> (like PostgreSQL, Cassandra, CockroachDB) work under the hood.
> Each phase builds on the previous one. Take your time — understanding beats speed.


---

## Phase 1: Storage Engine (YOU ARE HERE)

**Goal:** Learn how databases store and retrieve data on a single computer.

### What you'll learn

Think of a database as a fancy notebook. You need to decide:
- How do you **write** things down quickly?
- How do you **find** things again later?
- What happens if the **power goes out** mid-write?

### Step-by-step

#### 1a. MemTable (DONE)
An in-memory sorted map. All writes go here first because RAM is fast.

```
    PUT("banana", "yellow")
    PUT("apple", "red")
    PUT("cherry", "red")

    MemTable (sorted automatically):
    ┌─────────┬─────────┐
    │ apple   │ red     │
    │ banana  │ yellow  │
    │ cherry  │ red     │
    └─────────┴─────────┘
```

**Problem:** If the computer crashes, everything in RAM is lost!

#### 1b. Write-Ahead Log (WAL)
Before writing to the MemTable, first append the operation to a file on disk.
If the computer crashes, you replay the log to recover.

```
    Client says: PUT("apple", "red")

    Step 1: Write to disk log (safe!)    Step 2: Write to MemTable (fast!)
    ┌─────────────────────┐              ┌─────────────────────┐
    │ WAL File (on disk)  │              │ MemTable (in RAM)   │
    │                     │              │                     │
    │ PUT apple=red       │───then──────>│ apple -> red        │
    │ PUT banana=yellow   │              │ banana -> yellow    │
    │ DEL cherry          │              │                     │
    └─────────────────────┘              └─────────────────────┘
                                          
    Crash? Replay the WAL file → MemTable is rebuilt!
```

**What to build:** A class that appends operations to a file, and a method to replay it on startup.

#### 1c. SSTable (Sorted String Table)
The MemTable can't grow forever (RAM is limited). When it gets full,
**flush** it to disk as an immutable sorted file called an SSTable.

```
    MemTable gets full...
    ┌──────────────┐         Flush!        ┌──────────────────────┐
    │ MemTable     │  ──────────────────>   │ SSTable file (disk)  │
    │ apple=red    │                        │ apple=red            │
    │ banana=yellow│                        │ banana=yellow        │
    │ cherry=red   │                        │ cherry=red           │
    └──────────────┘                        └──────────────────────┘
    (now cleared,                           (immutable — never
     ready for new writes)                   modified after creation)

    Reading a key? Check MemTable first, then SSTables newest-to-oldest.
```

**What to build:** Write the MemTable to a sorted file, and read keys back using binary search.

#### 1d. LSM-Tree Compaction
Over time you get MANY SSTable files. Compaction merges them to:
- Remove deleted keys
- Remove old versions of updated keys
- Reduce the number of files to search

```
    Before compaction:                    After compaction:
    ┌──────────┐  ┌──────────┐           ┌──────────────────┐
    │ SSTable 1 │  │ SSTable 2 │          │ Merged SSTable   │
    │ apple=red │  │ apple=GREEN│   ──>   │ apple=GREEN      │
    │ banana=yel│  │ cherry=red │         │ banana=yellow    │
    └──────────┘  └──────────┘           │ cherry=red       │
                                          └──────────────────┘
    (apple was updated — old value discarded)
```

**What to build:** A background thread that merges SSTable files.

#### 1e. Bloom Filters
When reading, you check each SSTable for the key. But most SSTables
won't have it! A Bloom filter is a fast "maybe/definitely-not" check.

```
    GET("mango")
    
    SSTable 1 → Bloom filter says "DEFINITELY NOT HERE" → skip!   (saved time!)
    SSTable 2 → Bloom filter says "MAYBE HERE" → actually check   (found it!)
    SSTable 3 → Bloom filter says "DEFINITELY NOT HERE" → skip!   (saved time!)
```

A Bloom filter is a bit array + hash functions. It can have false positives
(says "maybe" when key isn't there) but NEVER false negatives
(never says "not here" when the key IS there).

**What to build:** A Bloom filter class, and attach one to each SSTable.

### Key concepts to understand
- **Write amplification** — data gets written multiple times (WAL → MemTable → SSTable → compaction)
- **Read amplification** — a read might check multiple SSTables
- **Space amplification** — duplicate keys exist until compaction cleans them up


---

## Phase 2: Network Layer

**Goal:** Make your database accessible over the network so multiple programs (or nodes) can talk to it.

### What you'll learn
Right now your DB only works inside one Java program. Real databases listen
on a network port so any program can connect and send commands.

### Step-by-step

#### 2a. TCP Server
A server that listens on a port and accepts client connections.

```
    ┌─────────┐       TCP connection        ┌─────────────────┐
    │ Client  │ ──────────────────────────>  │ Your DB Server  │
    │ (any    │   "PUT apple red"           │ Port 9876       │
    │  program)│ <──────────────────────────  │                 │
    └─────────┘       "OK"                  └─────────────────┘
```

**What to build:** A Java NIO server that accepts connections and reads/writes messages.

#### 2b. Client Protocol
Define a simple text protocol (like Redis uses):

```
    Client sends:           Server responds:
    ─────────────           ─────────────────
    PUT key value    ──>    OK
    GET key          ──>    VALUE value   (or NOT_FOUND)
    DEL key          ──>    OK
```

**What to build:** A parser that reads commands and calls your StorageEngine.

#### 2c. RPC Protocol (for node-to-node)
Nodes need to send more complex messages to each other (not just get/put).
Use Protobuf to define structured messages.

```
    Node A                                    Node B
    ┌──────────┐   Protobuf message          ┌──────────┐
    │          │   (binary, compact, fast)    │          │
    │ "replicate│ ─────────────────────────>  │ "got it, │
    │  this     │                             │  stored!" │
    │  entry"   │ <─────────────────────────  │          │
    └──────────┘   Protobuf response          └──────────┘
```

**What to build:** `.proto` files defining messages, and Java classes that send/receive them.

#### 2d. Node Discovery
How does a node find other nodes? Start simple with a seed list:

```
    Config file (seeds.txt):
    ┌───────────────────┐
    │ 192.168.1.10:9876 │      Node starts → contacts seeds → learns about others
    │ 192.168.1.11:9876 │
    │ 192.168.1.12:9876 │
    └───────────────────┘
```

**What to build:** A config that lists known nodes, and a handshake on startup.

### Key concepts to understand
- **Serialization** — converting objects to bytes to send over the network
- **Framing** — how do you know where one message ends and the next begins?
- **Backpressure** — what if the sender is faster than the receiver?


---

## Phase 3: Replication

**Goal:** Copy data to multiple nodes so it survives machine failures.

### What you'll learn
If your database is on ONE machine and that machine dies, all data is gone.
Replication keeps copies on multiple machines.

### Step-by-step

#### 3a. Leader-Follower Model
One node is the "Leader" — it handles all writes.
Other nodes are "Followers" — they get copies of the data and handle reads.

```
    Client                 Leader (Node A)           Followers
      │                        │                     ┌─────────┐
      │── PUT("x","1") ──────>│                     │ Node B  │
      │                        │── replicate ──────> │ x = 1   │
      │                        │                     └─────────┘
      │                        │                     ┌─────────┐
      │                        │── replicate ──────> │ Node C  │
      │                        │                     │ x = 1   │
      │<───── OK ─────────────│                     └─────────┘
      │
      │── GET("x") ──────────────────────────────── > Node B
      │<── "1" ──────────────────────────────────── < Node B
```

**What to build:** A leader that forwards WAL entries to followers over the network.

#### 3b. Sync vs Async Replication

```
    SYNCHRONOUS (safe but slow):
    Client ──> Leader ──> Follower says "got it!" ──> Leader says "OK" to client
    (Leader waits for follower before confirming — if follower is slow, client waits)

    ASYNCHRONOUS (fast but risky):
    Client ──> Leader says "OK" immediately ──> Leader sends to Follower later
    (Client gets fast response, but if Leader crashes before replicating, data is LOST)
```

**What to build:** A config toggle between sync and async replication.

#### 3c. Failover (manual first)
What happens when the leader crashes?

```
    BEFORE:                          AFTER (manual failover):
    ┌────────┐                       ┌────────┐
    │ Node A │ ← Leader    💥        │ Node A │  DEAD
    │ Node B │ ← Follower           │ Node B │ ← NEW Leader (promoted!)
    │ Node C │ ← Follower           │ Node C │ ← Follower (now follows B)
    └────────┘                       └────────┘
```

**What to build:** An admin command to promote a follower to leader.

### Key concepts to understand
- **Replication lag** — followers are slightly behind the leader
- **Eventual consistency** — followers will "eventually" have the same data
- **Split brain** — what if two nodes both think they're the leader? (Phase 4 fixes this!)


---

## Phase 4: Consensus (Raft)

**Goal:** Automate leader election so the cluster heals itself when a node crashes.

### What you'll learn
In Phase 3, a human had to promote a new leader. Raft is an algorithm
that lets nodes **vote** to elect a leader automatically.

### Step-by-step

#### 4a. Node States
Every node is in one of three states:

```
    ┌───────────┐   timeout, start election   ┌─────────────┐
    │           │ ──────────────────────────>  │             │
    │ FOLLOWER  │                              │ CANDIDATE   │
    │           │ <──────────────────────────  │             │
    └───────────┘   discovers current leader   └─────────────┘
                                                      │
                                               wins majority vote
                                                      │
                                                      v
                                               ┌─────────────┐
                                               │   LEADER    │
                                               └─────────────┘
```

#### 4b. Leader Election
Nodes use "terms" (like election seasons). If a follower doesn't hear from
the leader for a while, it becomes a candidate and asks for votes.

```
    Term 1: Node A is leader
    
    Node A crashes! 💥
    
    Node B hasn't heard from A in 300ms...
    Node B: "I'm now a CANDIDATE for Term 2. Vote for me!"
    
    Node C: "You have my vote for Term 2!" ✓
    Node B: "I have 2/3 votes (majority). I am the LEADER for Term 2!"
    
    Term 2: Node B is leader ✓
```

**What to build:** Election timer, vote request/response RPCs, term tracking.

#### 4c. Log Replication
The leader keeps an ordered log of all operations. It sends log entries
to followers who must apply them in the same order.

```
    Leader's Log:          Follower's Log:
    ┌───┬──────────────┐   ┌───┬──────────────┐
    │ 1 │ PUT x=1      │   │ 1 │ PUT x=1      │  ✓ matches
    │ 2 │ PUT y=2      │   │ 2 │ PUT y=2      │  ✓ matches
    │ 3 │ DEL x        │   │ 3 │ DEL x        │  ✓ matches
    │ 4 │ PUT z=3      │   │   │ (catching up) │  ← leader sends entry 4
    └───┴──────────────┘   └───┴──────────────┘
    
    "Committed" = majority of nodes have the entry → safe to apply
```

**What to build:** AppendEntries RPC, commit index tracking, log persistence.

#### 4d. Safety
Raft guarantees that once an entry is committed, it can NEVER be lost —
even if nodes crash and restart. This is the hardest part to get right.

**What to build:** Vote restrictions (only vote for candidates with up-to-date logs).

### Key concepts to understand
- **Quorum** — a majority of nodes (e.g., 2 out of 3, 3 out of 5)
- **Split brain** — Raft prevents this by requiring a majority vote
- **Safety vs Liveness** — Raft is always safe, but may be temporarily unavailable during elections


---

## Phase 5: Partitioning (Sharding)

**Goal:** Split data across multiple nodes so you can store more than one machine can hold.

### What you'll learn
So far, every node has a copy of ALL data. But what if you have 10TB of data
and each machine only has 1TB of disk? You split the data into **partitions**.

### Step-by-step

#### 5a. Hash Partitioning
Hash the key to decide which node stores it:

```
    hash("apple")  % 3 = 0  → Node A
    hash("banana") % 3 = 1  → Node B  
    hash("cherry") % 3 = 2  → Node C
    hash("date")   % 3 = 0  → Node A

    Node A            Node B            Node C
    ┌──────────┐      ┌──────────┐      ┌──────────┐
    │ apple    │      │ banana   │      │ cherry   │
    │ date     │      │          │      │          │
    └──────────┘      └──────────┘      └──────────┘
```

**Problem:** If you add/remove a node, `% 3` becomes `% 4` and almost ALL
keys move! That's expensive.

#### 5b. Consistent Hashing
Instead of `% N`, place nodes on a ring. Each key goes to the next node clockwise.
Adding a node only moves keys in one section of the ring.

```
              0°
              │
        Node A●───────── ●Node D (new!)
       /      │            \
     /        │              \
    ●Node C   │            Node B●
       \      │           /
         \    │         /
           ───●───────
             180°
    
    Adding Node D only moves a few keys from Node A → minimal disruption!
```

**What to build:** A hash ring, virtual nodes, and key routing.

#### 5c. Request Routing
The client needs to find the right node for a key:

```
    Option 1: Client knows       Option 2: Any node
    the ring layout               forwards to correct node
    
    Client ──> Node B             Client ──> Node A ──forward──> Node B
    (smart client)                (simple client, smart server)
```

**What to build:** A router that maps keys to partitions to nodes.

### Key concepts to understand
- **Hot spots** — some keys get way more traffic (e.g., a celebrity's profile)
- **Rebalancing** — moving data when nodes join or leave
- **Partition + Replication** — each partition is replicated for safety


---

## Phase 6: Transactions

**Goal:** Allow multiple operations to succeed or fail as a single unit.

### What you'll learn
What if you need to transfer money: subtract from account A AND add to account B?
If the computer crashes between these two operations, money disappears!
Transactions make this atomic — all or nothing.

### Step-by-step

#### 6a. ACID Properties

```
    A - Atomicity:    All operations succeed, or ALL are rolled back
    C - Consistency:  The database moves from one valid state to another
    I - Isolation:    Concurrent transactions don't interfere with each other
    D - Durability:   Once committed, data survives crashes
```

#### 6b. MVCC (Multi-Version Concurrency Control)
Instead of locking data, keep multiple versions. Each transaction sees
a consistent snapshot.

```
    Time ──────────────────────────────────────>
    
    Transaction T1 starts (sees snapshot at time=5)
    Transaction T2 starts (sees snapshot at time=5)
    
    T1: PUT balance=50   (writes version 6)
    T2: GET balance       → still sees 100! (its snapshot is time=5)
    T2: commits
    T1: commits
    
    After both commit: balance = 50 (T1's write)
    
    Key: "balance"
    ┌─────────┬─────────┬──────────┐
    │ Version │ Value   │ Written  │
    │ 6       │ 50      │ T1       │
    │ 5       │ 100     │ T0       │  ← old version kept for snapshots
    └─────────┴─────────┴──────────┘
```

**What to build:** Version stamps on each value, snapshot reads based on transaction start time.

#### 6c. Two-Phase Commit (2PC) — for distributed transactions
When a transaction spans multiple nodes, all nodes must agree to commit:

```
    Coordinator               Node A              Node B
        │                        │                    │
        │── "PREPARE to commit"─>│                    │
        │── "PREPARE to commit"──────────────────────>│
        │                        │                    │
        │<── "YES, I can" ──────│                    │
        │<── "YES, I can" ──────────────────────────│
        │                        │                    │
        │── "COMMIT!" ─────────>│                    │
        │── "COMMIT!" ──────────────────────────────>│
        │                        │                    │
        Phase 1: PREPARE          Phase 2: COMMIT
        
    If ANY node says "NO" in Phase 1 → ALL nodes ABORT
```

**What to build:** A coordinator that runs the 2PC protocol across nodes.

### Key concepts to understand
- **Serializability** — transactions behave as if they ran one at a time
- **Write skew** — two transactions read the same data and make conflicting writes
- **Deadlocks** — two transactions each waiting for the other's lock


---

## Phase 7: Query Layer

**Goal:** Let users write queries instead of just get/put commands.

### What you'll learn
Real databases let you ask questions like "find all users older than 25".
You'll build a simple query language on top of your key-value store.

### Step-by-step

#### 7a. Query Parser
Parse a simple SQL-like syntax into a structured query:

```
    Input:  "SELECT * FROM users WHERE age > 25"
    
    Parser turns this into:
    ┌──────────────────────┐
    │ Query                │
    │  table: "users"      │
    │  filter: age > 25    │
    │  fields: all (*)     │
    └──────────────────────┘
    
    The storage engine uses this structured query to find matching keys.
```

**What to build:** A tokenizer and parser for basic SELECT/INSERT/DELETE.

#### 7b. Secondary Indexes
Your key-value store can only look up by key. What about finding by value?
A secondary index maps values back to keys.

```
    Primary data (key = user ID):         Secondary index (on "city"):
    ┌──────┬────────────────────┐         ┌───────────┬──────────────┐
    │ u1   │ {name:Ana, city:NY}│         │ NYC       │ [u1, u3]     │
    │ u2   │ {name:Bob, city:SF}│         │ SF        │ [u2]         │
    │ u3   │ {name:Cat, city:NY}│         └───────────┴──────────────┘
    └──────┴────────────────────┘
    
    "SELECT * WHERE city = 'NYC'" → index says u1, u3 → fetch those two rows
    (without index: scan ALL rows — slow!)
```

**What to build:** An index structure that's updated on every write.

#### 7c. Scatter-Gather Queries
When data is partitioned, a query might need data from multiple nodes:

```
    Query: "SELECT * WHERE age > 25"
    
    Coordinator
        │
        ├──> Node A: "find age > 25 in YOUR partition" ──> [result A]
        ├──> Node B: "find age > 25 in YOUR partition" ──> [result B]  
        ├──> Node C: "find age > 25 in YOUR partition" ──> [result C]
        │
        └──> Merge [result A] + [result B] + [result C] → final result
```

**What to build:** A coordinator that fans out queries and merges results.


---

## Recommended Reading
- *Designing Data-Intensive Applications* by Martin Kleppmann — **the** book for this project
- Raft paper — https://raft.github.io/raft.pdf
- Google Bigtable paper — where LSM trees and SSTables originated
- Amazon Dynamo paper — consistent hashing, quorums, and eventual consistency
