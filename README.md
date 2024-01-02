# XT-fiddle

### Development

```sh
yarn install
```
Running the shadow server.
```sh
npx shadow-cljs server
```
With cider you should now be able to connect a cljs REPL.

With other IDE's you might need to run.
```sh
npx shadow-cljs watch app
```

To start a server you need to run a "normal" Clojure REPL and call
```clj
(def server (start {:join false}))
```
in the `server` namespace.

### Creating jar (todo)

Executing the program can either be done via
```
clj -M -m main :arg1 :arg2
```
or by compiling a jar via
```
clj -T:build clean
clj -T:build jar
```
and executing it via
```
java -jar target/lib-0.1.4.jar :arg1 :arg2
```
## License
