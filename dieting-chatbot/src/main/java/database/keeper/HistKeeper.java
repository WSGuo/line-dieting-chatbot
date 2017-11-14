package database.keeper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;

/**
 * History keeper to store and load user meal history in the redis cache.
 * The valid JSONObject format is MealJSON defined by database APIs.
 * @author mcding
 * @version 1.2.1
 */
@Slf4j
public class HistKeeper extends SerializeKeeper {
    /**
     * The identifier of all user meal history.
     */
    private static final String prefix = "hist";

    /**
     * The valid fields of MealJSON.
     */
    private static final List<String> fields = Arrays.asList(
            "date", "number_of_meal", "food", "time_created"
    );

    /**
     * constructor
     * Default constructor.
     */
    public HistKeeper() {
        super();
    }

    /**
     * Constructor which uses external redis connection.
     * @param jedids external redis connection
     */
    HistKeeper(Jedis jedids) {
        this.jedis = jedis;
    }


    /**
     * Get the latest rows of user hist.
     * @param key user id
     * @param number number of latest result to return
     * @return JSONArray array of recent JSONObjects
     */
    @Override
    public JSONArray get(String key, int number) {
        JSONArray jsonArray = rangeList(prefix, key, number);
        if (jsonArray.length() == 0) {
            log.error("Attempting to search user meal hist that does not exist.");
            return null;
        }
        if (!checkValidity(jsonArray, fields)) {
            log.error("Failed to load user meal history due to wrongly formatted MealJSON.");
            return null;
        }
        return jsonArray;
    }

    /**
     * Add new user hist to cache.
     * @param key user id
     * @param jsonObject new row to add to the redis cache
     * @return whether appending operation is successful or not
     */
    public boolean set(String key, JSONObject jsonObject) {
        if (!checkValitidy(jsonObject, fields)) {
            log.error("Invalid formatted MealJSON.");
            return false;
        }
        return appendList(prefix, key, jsonObject);
    }
}