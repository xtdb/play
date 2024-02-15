FROM clojure:tools-deps-alpine AS frontend-build
WORKDIR /app

# Install build deps
RUN apk add --update nodejs npm yarn

# Install node deps
COPY package.json .yarnrc.yml yarn.lock .
RUN yarn install

# Install clojure build deps
COPY deps.edn .
RUN clojure -A:cljs -P

# Build the frontend
# TODO: Copy over only cljs files?
COPY . .
RUN npx shadow-cljs release app


FROM clojure:tools-deps-alpine
WORKDIR /app

# Install the runtime deps
COPY deps.edn .
RUN clojure -P

# Add the source & frontend code
COPY --from=frontend-build /app/resources/public/js/compiled /app/resources/public/js/compiled
# TODO: Copy over only clj files?
COPY . .
CMD clojure -X:prod
