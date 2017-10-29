package database.querier;

import org.json.JSONArray;

import java.sql.Connection;
import java.sql.ResultSet;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link PartialFoodQuerier}
 * Food querier which implements partial match search.
 */
@Slf4j
public class PartialFoodQuerier extends FoodQuerier {
    /**
     * constructor
     * Default constructor.
     */
    PartialFoodQuerier() {
        super();
    }

    /**
     * constructor
     * Constructor which uses external sql connection.
     * @param sql external sql connection
     */
    PartialFoodQuerier(Connection sql) {
        super();
        this.sql = sql;
    }

    /**
     * constructor
     * Set the query limit and levenshtein algorithm parameters.
     * @param queryLimit number of rows to return when searching
     */
    PartialFoodQuerier(int queryLimit) {
        super(queryLimit);
    }

    /**
     * get
     * Partial search for a food.
     * @param key string to search
     * @return JSONArray an array of FoodJSON
     */
    @Override
    public JSONArray get(String key) {
        String query = String.format("SELECT * FROM %s WHERE shrt_desc ILIKE '%%%s%%' LIMIT %d;",
                table, key, queryLimit);
        ResultSet rs = executeQuery(query);
        return parseResult(rs, fields);
    }
}
