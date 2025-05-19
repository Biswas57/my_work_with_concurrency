# RSheets Key Design Considerations:

### New CellData Struct
• Encapsulates each cell’s state (expression, version, value) into a single struct,.
• Using the atomic counter allowed me to make room for decent progression in my code from a single to a chained DAG dependency worker.

### Effective Use of Closures
• Used inline closures (e.g. the retrieve_value closure in build_env) abstract repeated logic to clean up and keep helper code local to its use-case.

### Stage 5: DAG, Topological Sort w/ Khan's Algorithm (Probably the one I want feedback on the MOST)
Progression from Single to Chained dependency worker thread implementation

Context: 
• Dependencies are chained and potentially infinite → a graph (with measures to handle cycles) is the best choice.

Original Brute Force Approach: 
• In my single dependency-worker thread, I simply iterated through all the cells stored in the spreadsheet hash map. 
– Because of the cell data struct I made (stored expression), I was able to recompute the values if they were different. 
- Every 50 ms would work almost instantly; mutex held during computation. 
- Used an atomic thread counter to ensure updates only happened when necessary.

Why this wouldn't work for chaining: 
• Chaining could be arbitrarily long, requiring huge number of loops for an absurdly large dependency chain. 
– Extremely inefficient and unreliable for concurrent processes with many connections. 
• It might work if updates occurred often enough, but in a real-world scenario with thousands of user connections
- many simultaneous operations, dependencies simply wouldn't update fast enough. 
• Since I couldn't emulate this with 1000+ connections, I set the worker thread loop to sleep 200 ms and complete 10 loops. 
– Emulation is accurate because: simulating ↑ connections ~= simulating ↓ worker thread repetitions → overall fewer updates.

My Approach: Topological Sort w/ Khan's Algorithm. 
• Dependency chains reminded me of a tree in nature, but due to potential deep cycles they must be represented as a graph.
    - See tree_vs_graph.png
• Revised approach: Use a directed acyclic graph (DAG) to represent dependencies. 
– Dependencies (scalars, vectors, matrices) + potential cyclic chains: 
    - a DAG using Khan's algorithm to order worker thread updates (and detect cycles) is the best approach.

Breakdown of Khan's Algorithm (in context): 
• Every 50 ms, the worker thread loops. Each Loop:
• Uses a graph (hashmap) where the key is a cell identifier and the value is a Vec<CellIdentifier> of dependent nodes. 
• Calculates the in-degree of every node in the graph. 
• Performs Khan's algorithm starting at nodes with an in-degree of 0. 
• Nodes with in-degree 0 indicate dependencies that have been resolved. 
• The algorithm iterates through all values in the graph hashmap: 
    – Each time a node (cell) appears in a dependent array, update an in-degree hashmap to count its dependencies. 
    – For cells with expressions that weren't counted (i.e. not appearing in the values section), assign an in-degree accordingly. 
• Push all nodes with an in-degree of 0 onto a queue. 
• Pop from the queue and reevaluate the expression. 
    – This ensures all values are recalculated from the least-dependent to the most-dependent cells in the chain. 
    – It solves the infinite iteration problem since processing stops when the queue is empty. 
• For vector and matrix ranges (e.g., A1-B3): 
    – Suppose A9 = sum(A1-B3): 
    - All cells in A1-B3 have A9 in their dependents list, so all must be updated during Khan’s algorithm before A9 is recalculated (managed by the queue). 
• Update the value on the spreadsheet and compare recalculation to the current value. 
    – For cells with ranged dependencies (vector, matrix), it is implicative that all cells in said range must be recalculate before we push the dependent cell onto the queue. 
• Repeat the process until the queue is empty. 
• If any nodes have an in-degree > 0 after processing, it means they are in a cycle; thus, all cells in the cycle show as errors.
    - See topological_sort_graph.png below notes are referring to
    - Note: The graph doesn't store expressions but they can be retrieved from a clone of the spreadsheet.
    - Note: This cycle will always have indegree >= 1, therefore it never gets processed (popped from the queue) because dependencies are never all resolved
