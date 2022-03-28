# mt-scala-loci-examples

## How to run the showcase applications

- Run `sbt pack` in the root directory

### Prime Counter

- clone the https://github.com/curtisseizert/CUDASieve
  - copy it into the same directory that contains mt-scala-loci-examples
  - build the project according to its readme
  - using this requires a CUDA-capable NVIDIA GPU, some changes to the makefile may be necessary, depending on the GPU model
- Start GPU-Server: `./target/pack/bin/primesieve-gpu`
- Start Server: `./target/pack/bin/primesieve-server`
- Start Client: `./target/pack/bin/primesieve-client`
- Type in positive numbers into the Client terminal and see the results
  - also look for the GPU-Server and Server terminal, indicating that they execute a Client request

### TreeDB

In this example, the tree consists of three nodes, namely the Root and its two children Left and Right.

- Start Root: `./target/pack/bin/treedb-root`
- Start Left: `./target/pack/bin/treedb-left`
- Start Right: `./target/pack/bin/treedb-right`
- Start Client: `./target/pack/bin/treedb-client`
- Initialize the key ranges by hitting Enter in the Root terminal
  - the Root, Left, and Right terminal now show their respective key range and the key ranges of their children
- Type in commands at the Client terminal:
  - `insert <key> <value>`
  - `get <key>`
  - `delete <key>`
  - `<key>` should be some integer and `<value>` some string
  - check the Root, Left, and Right terminal for the output text that indicates they are handling a certain command

### App Sessions

In this example, we set up a system with two Servers and two Clients.

- Start Gateway: `./target/pack/bin/sessions-gateway`
- Start DB: `./target/pack/bin/sessions-db`
- Start Server 1: `./target/pack/bin/sessions-server`
- Start Server 2: `./target/pack/bin/sessions-server`
- Start Client 1: `./target/pack/bin/sessions-client`
- Start Client 2: `./target/pack/bin/sessions-client`
- Start session for Client 1:
  - hit Enter
  - type in the username and hit Enter
  - type in the password and hit Enter
  - now we should see a welcome message
  - type in some strings (those are added to the DB for this user)
  - hit Enter without any input -> this shows the entries in the DB
  - look at the terminals for the two Servers:
    - all requests for this Client should have been handled by the same Server
- Start session for Client 2:
  - repeat the steps of Client 1
  - look at the terminal of the other Server (i.e., the one that didn't handle Client 1):
    - all Client 2 requests should have been handled by this Server (as the Gateway should balance the load)
    
### Trust

From a functional perspective this example is not too interesting, as it rather conceptual. But we can run it anyway.

- Start TrustedKeyDB: `./target/pack/bin/trust-TrustedKeyDB`
- Start PublicKeyDB: `./target/pack/bin/trust-PublicKeyDB`
- Start KeyManager: `./target/pack/bin/trust-KeyManager`
- Start SuperVisor: `./target/pack/bin/trust-SuperVisor`
- Start ResourceManager: `./target/pack/bin/trust-ResourceManager`
- Start Server: `./target/pack/bin/trust-Server`
- Start Client: `./target/pack/bin/trust-Client`
- Hit Enter at the Client terminal
  - the Client will now send a request to the Server, but it is not yet trusted
- Type in "no" at the SuperVisor to deny the Client trust
  - the Client output should indicate that it did not get a resource
- Hit Enter at the Client terminal again
  - the Client will send another request to the Server, still not being trusted
- Type in "yes" at the SuperVisor to trust the Client this time
  - the Client output should indicate that it did receive a resource
- Now every further Client request (hitting Enter) should be trusted automatically