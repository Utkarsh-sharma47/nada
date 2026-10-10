# Assignment checklist

- [x] `POST /people` creates a person, assigns the id, and returns 201
- [x] `POST /knows` stores one mutual connection and treats repeats as success
- [x] `GET /people/{id}/contacts` returns only direct contacts, or an empty array
- [x] `GET /path` returns a shortest path of at most three hops, or `{"connected":false}`
- [x] Same person returns a zero-hop path
- [x] Unknown ids return 404 `{"error":"no person <id>"}`
- [x] Invalid JSON, blank names, self-connections, and bad query parameters return 400
- [x] Unicode names are stored and returned unchanged
- [x] Invalid requests do not stop the server or change stored data
- [x] In-memory graph stays mutual under concurrent updates
- [x] Unit tests, HTTP tests, and `mvn clean verify` pass
- [x] README commands, port selection, and a running jar were checked
- [x] AI use is disclosed in the README

Manual server check used port 9090 because port 8080 was already taken on this machine. The default port is 8080, covered by `NadaApplicationTest` and the startup message.
