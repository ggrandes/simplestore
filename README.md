# SimpleStore

SimpleStore is an Idempotent RESTful PUT/GET/DELETE key/value store in Java. Project is Open source (Apache License, Version 2.0).

### Current Stable Version is [1.0.0](https://maven-release.s3.amazonaws.com/release/org/javastack/simplestore/1.0.0/simplestore-1.0.0.war)

---

## DOC

### Supported features

  - [x] Put
  - [x] Get
  - [x] Delete

### Limits

  - Key names are limited to safe URL caracters: `A-Za-z0-9._-`
  - All data are stored as plain files in a single directory, in some filesystems, like FAT32 are limited to 65k files.
  - If you use a case-insensitive filesystem (like FAT32) Key names can collide.
  - Large files (+2GB) are supported, but in some filesystems (like FAT32) can be a problem.
  - [AIO/Sendfile](https://tomcat.apache.org/tomcat-7.0-doc/aio.html#Asynchronous_writes) are supported, but need APR/NIO connector in Tomcat. 

### Configuration

SimpleStore only need directory path to store data, this is configured with property name `org.javastack.simplestore.directory`, that can be configured in a Context Param, System Property, System Environment, or file named `simplestore.properties` (located in classpath) 

### Sample cURL usage

```bash
# PUT "test data" in keyname "k1.txt"
curl -X PUT --data-binary "test data" http://localhost:8080/simplestore/k1.txt

# GET data from keyname "k1.txt"
curl -X GET http://localhost:8080/simplestore/k1.txt

# DELETE keyname "k1.txt"
curl -X DELETE http://localhost:8080/simplestore/k1.txt
```

---
Inspired in [Amazon S3](https://aws.amazon.com/s3/), this code is Java-minimalistic version.
