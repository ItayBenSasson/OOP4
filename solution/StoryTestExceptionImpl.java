package solution;
import org.junit.Assert;
import provided.StoryTestException;
public class StoryTestExceptionImpl extends StoryTestException {
    String firstFailedSentence;
    String expected;
    String result;
    int numFails;

    public StoryTestExceptionImpl(String sentence, String expected_str, String result_str, int fails)
    {
        firstFailedSentence = sentence;
        expected = expected_str;
        result = result_str;
        numFails = fails;
    }
    /**
     * Returns a string representing the sentence
     * of the first Then sentence that failed
     */
    public String getSentance()
    {
        return firstFailedSentence;
    }

    /**
     * Returns a string representing the expected value from the story
     * of the first Then sentence that failed.
     */
    public String getStoryExpected()
    {
        return expected;
    }

    /**
     * Returns a string representing the actual value.
     * of the first Then sentence that failed.
     */
    public String getTestResult()
    {
        return result;
    }

    /**
     * Returns an int representing the number of Then sentences that failed.
     */
    public int getNumFail()
    {
        return numFails;
    }
}

