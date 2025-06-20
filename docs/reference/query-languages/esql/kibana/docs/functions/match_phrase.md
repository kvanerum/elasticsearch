% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### MATCH PHRASE
Use `MATCH_PHRASE` to perform a [`match_phrase`](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-match-query-phrase) on the
specified field.
Using `MATCH_PHRASE` is equivalent to using the `match_phrase` query in the Elasticsearch Query DSL.

MatchPhrase can be used on [text](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/text) fields, as well as other field types like keyword, boolean, or date types.
MatchPhrase is not supported for [semantic_text](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/semantic-text) or numeric types.

MatchPhrase can use [function named parameters](https://www.elastic.co/docs/reference/query-languages/esql/esql-syntax#esql-function-named-params) to specify additional options for the
match_phrase query.
All [`match_phrase`](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-match-query-phrase) query parameters are supported.

`MATCH_PHRASE` returns true if the provided query matches the row.

```esql
FROM books
| WHERE MATCH_PHRASE(author, "William Faulkner")
```
