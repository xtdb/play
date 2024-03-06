# XT-fiddle

The code behind [XT fiddle](https://fiddle.xtdb.com/), a web tool for exploring the [XTDB database](https://xtdb.com).

## Running

Want to run it locally? Use the docker container:

```sh
docker run -p 3000:8000 --pull-always ghcr.io/xtdb/xt-fiddle
```

Then visit [https://localhost:3000](https://localhost:3000).

## Deploy

This repo is setup to deploy on *git tags*, specifically `vX.Y.Z` tags.

So deploying is as simple as:
1. Commit & push your changes
2. `git tag vX.Y.Z`
3. `git push --tags`

## Development

For the front end you'll need to install deps:

```sh
yarn install
```

After that for development you'll need two processes:

### Shadow-cljs

This can be started with:

```sh
npx shadow-cljs watch app
```

Now shadow-cljs will be watching for changes recompiling.
See the [shadow-cljs docs](https://shadow-cljs.github.io/docs/UsersGuide.html) for more.

### Clojure repl

Just start your repl in the ordinary way (or be sneaky like me and use the repl from shadow-cljs (not recommended)).

Then you can start the server by running `(go)` in the `user` namespace.

You should than be able to browse a dev build at [http://localhost:8000](http://localhost:8000).


### Infrastructure

Infrastructure is spread across three files:
- cloudformation/deploy.yml
  - Contains most of the infra
- cloudformation/service.yml
  - Just the bits that the github actions need to deploy
- cloudformation/github-keys.yml
  - Contains the user used by github to deploy

These are glued together by liberal use of ssm parameters and [dynamic references](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/dynamic-references.html).

On initial deployment they *must* be deployed in the above order.

<details>

<summary>Some example commands</summary>

Create:
```sh
aws cloudformation create-stack --capabilities CAPABILITY_IAM --stack-name xt-fiddle--github --template-body "file://$(pwd)/cloudformation/github-keys.yml"
```

Update:
```sh
aws cloudformation update-stack --capabilities CAPABILITY_IAM --stack-name xt-fiddle--github --template-body "file://$(pwd)/cloudformation/github-keys.yml"
```

Delete
```sh
aws cloudformation delete-stack --stack-name xt-fiddle--github
```

</details>
