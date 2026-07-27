// Karma drives Mocha inside the browser, where the Gradle-side `useMocha { timeout }` does not
// reach — the limit has to come through Karma's client config. Matches the Node timeout: the
// recall/purity tests index thousands of vectors and legitimately run for seconds.
config.set({
    client: {
        mocha: {
            timeout: 60000,
        },
    },
});
