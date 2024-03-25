package solution;

import junit.framework.AssertionFailedError;
import org.junit.Assert;
import org.junit.ComparisonFailure;
import provided.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.InvalidParameterException;

public class StoryTesterImpl implements StoryTester {

    private Object objectBackup;

    String firstFailedSentence;
    String expected;
    String result;
    int numFails;

    /** Creates and returns a new instance of testClass **/
    private static Object createTestInstance(Class<?> testClass) throws Exception {
        // This method uses only the default constructor.
        try {
            // Try constructing a new instance using the default constructor of testClass
            return testClass.getConstructor().newInstance();
        } catch (Exception e) {
            // On failure, we assume the reason was the class is enclosed.
            // Enclosed classes have a secret parameter - their enclosing instance.
            // Create an enclosing instance:
            Object enclosingInstance = createTestInstance(testClass.getEnclosingClass());
            return testClass.getConstructor(testClass.getEnclosingClass()).newInstance(enclosingInstance);
        }
    }

    /** Returns true if c has a copy constructor, or false if it doesn't **/
    private boolean copyConstructorExists(Class<?> c){
        try {
            c.getDeclaredConstructor(c);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Assigns into objectBackup a backup of obj.
     /** See homework's pdf for more details on backing up and restoring **/
    private void backUpInstance(Object obj) throws Exception {
        Object res = createTestInstance(obj.getClass());
        Field[] fieldsArr = obj.getClass().getDeclaredFields();
        for(Field field : fieldsArr){
            field.setAccessible(true);
            Object fieldObject = field.get(obj);
            if (fieldObject == null) {
                field.set(res, null);
                continue;
            }
            Class<?> fieldClass = fieldObject.getClass();

            // getDeclaredMethod vs getMethod:
            // getDeclaredMethod can access private/protected. getMethod can access inherited.
            Object fieldObjectClone;
            if(fieldObject instanceof Cloneable){
                // field.set(res, fieldObject.clone());
                // This doesn't work: why? because .clone() is protected! Let's use reflection:
                Method clone = Object.class.getDeclaredMethod("clone");
                clone.setAccessible(true);
                fieldObjectClone = clone.invoke(fieldObject);
            }
            else if(copyConstructorExists(fieldClass)){
                Constructor<?> cons = fieldClass.getDeclaredConstructor(fieldClass);
                cons.setAccessible(true);
                fieldObjectClone = cons.newInstance(fieldObject);
            }
            else{
                fieldObjectClone = fieldObject;
            }
            field.setAccessible(true);
            field.set(res, fieldObjectClone);
        }
        this.objectBackup = res;
    }

    /** Assigns into obj's fields the values in objectBackup fields.
     /** See homework's pdf for more details on backing up and restoring **/
    private void restoreInstance(Object obj) throws Exception{
        Field[] classFields = obj.getClass().getDeclaredFields();
        for(Field field : classFields) {
            field.setAccessible(true);
            Object value = field.get(this.objectBackup);
            field.set(obj, value);
        }
    }

    /** Returns the matching annotation class according to annotationName (Given, When or Then) **/
    private static Class<? extends Annotation> GetAnnotationClass(String annotationName){
        switch (annotationName) {
            case "Given":
                return Given.class;
            case "When":
                return When.class;
            case "Then":
                return Then.class;
            default:
                return null;
        }
    }

    static String getAnnotatedSentence(Method method, Class<? extends Annotation> annotationClass) {
        Object annotation = method.getAnnotation(annotationClass);
        if (annotation == null) return null;

        try {
            return (String) annotationClass.getDeclaredMethods()[0].invoke(annotation);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Annotation class " + annotationClass + " did not look correct! It must have a " +
                            "single invokable method that takes no parameters besides `this` and returns a String."
            );
        }
    }

    static Method findMethodByAnnotation(Class<?> c, Class<? extends Annotation> annotationClass, String sentenceSub) {
        for (Method method : c.getDeclaredMethods()) {
            // Is this annotated?
            String sentence = getAnnotatedSentence(method, annotationClass);
            if (sentence == null) continue;

            // Is this annotated the same as sentenceSub?
            int end = sentence.lastIndexOf(' ');
            String sentenceWithoutParameter = sentence.substring(0, end);
            if (!sentenceWithoutParameter.equals(sentenceSub)) continue;

            // TODO: Do we need to check the parameter of the annotated method too?

            return method;
        }
        // Method was not found in this class!
        // Maybe up above?
        return (c.getSuperclass() != null) ?
                findMethodByAnnotation(c.getSuperclass(), annotationClass, sentenceSub) :
                null;
    }

    WordNotFoundException newWordNotFoundException(String annotationName) {
        switch (annotationName) {
            case "When":
                return new WhenNotFoundException();
            case "Then":
                return new ThenNotFoundException();
            case "Given":
                return new GivenNotFoundException();
            default:
                throw new InvalidParameterException("Bad annotation name!");
        }
    }

    static Object parseParameter(String parameter) {
        try {
            return Integer.parseInt(parameter);
        } catch (NumberFormatException e) {
            return parameter;
        }
    }

    @Override
    public void testOnInheritanceTree(String story, Class<?> testClass) throws Exception {
        if((story == null) || testClass == null) throw new IllegalArgumentException();
        boolean then_failed = false, story_failed = false, when_case = false;
        this.numFails = 0;
        Object testInstance = createTestInstance(testClass);

        for(String sentence : story.split("\n")) {
            // Parsing
            String[] words = sentence.split(" ", 2);
            String annotationName = words[0];
            Class<? extends Annotation> annotationClass = GetAnnotationClass(annotationName);
            String sentenceSub = words[1].substring(0, words[1].lastIndexOf(' ')); // Sentence without the parameter and annotation
            String parameter = sentence.substring(sentence.lastIndexOf(' ') + 1);

            // Setting up the method call
            Object parameterObj = parseParameter(parameter);
            Method method = findMethodByAnnotation(testClass, annotationClass, sentenceSub);
            if (method == null) throw newWordNotFoundException(annotationName);
            // (Must be accessible for us - let's make it accessible).
            method.setAccessible(true);

            // Call it!
            Class<?> our_test = null;
            try {
                backUpInstance(testInstance);
                method.invoke(testInstance, parameterObj);
                if (annotationName.equals("When") && !when_case) {
                    if (then_failed) {
                        restoreInstance(testInstance);
                        break;
                    }
                    backUpInstance(testInstance);
                    when_case = true;

                }
                if (annotationName.equals("Then") && when_case) {
                    when_case = false;
                }
                our_test = testClass;
                while (our_test != null) {
                    try {
                        Method then = findMethodByAnnotation(our_test, Then.class, sentenceSub);
                        if (then != null) {
                            backUpInstance(testInstance);
                            then.invoke(testInstance, parameterObj);
                            break;
                        }
                    } catch (Exception e) {
                    }
                    our_test = our_test.getSuperclass();
                }
            } catch (InvocationTargetException e) {
                // If the method threw an exception, we need to handle it.
                Throwable cause = e.getCause();
                if (cause instanceof AssertionFailedError) {
                    // If it's an assertion failure, we need to handle it.
                    AssertionFailedError assertionFailedError = (AssertionFailedError) cause;
                    if (then_failed) {
                        // If we already failed, we need to restore the object and break.
                        restoreInstance(testInstance);
                        break;
                    }
                    // Otherwise, we need to save the failure information.
                    then_failed = true;
                    story_failed = true;
                    this.firstFailedSentence = sentence;
                    this.expected = assertionFailedError.getExpected();
                    this.result = assertionFailedError.getActual();
                    this.numFails++;
                } else {
                    // If it's not an assertion failure, we need to rethrow it.
                    throw e;
                }
                //we haven't found a method in the inheritance tree
                if (our_test == Object.class) {
                    throw newWordNotFoundException(annotationName);
                }
            }

        }

        if (story_failed) {
            throw new StoryTestExceptionImpl(this.firstFailedSentence, this.numFails, this.expected, this.result);
        }

        // TODO: Throw StoryTestExceptionImpl if the story failed.
    }


    @Override
    public void testOnNestedClasses(String story, Class<?> testClass) throws Exception {
        if((story == null) || testClass == null) throw new IllegalArgumentException();
        try{
            testOnInheritanceTree(story, testClass);
        }
        catch (GivenNotFoundException e){
            boolean no_story = true;
            for (Class<?> innerClass : testClass.getDeclaredClasses()){
                try {
                    testOnInheritanceTree(story, innerClass);
                }
                catch (GivenNotFoundException e1){
                    continue;
                }
                no_story = false;
                break;
            }
            if (no_story) throw new GivenNotFoundException();
        }
    }
}