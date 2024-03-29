package solution;

import org.junit.ComparisonFailure;
import provided.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
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
        if (!testClass.isMemberClass() || Modifier.isStatic(testClass.getModifiers())) {
            Constructor<?> ctor = testClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } else {
            // Enclosed non-static classes have a secret parameter - their enclosing instance.
            // Create an enclosing instance:
            Class<?> enclosing = testClass.getEnclosingClass();
            Object enclosingInstance = createTestInstance(enclosing);
            Constructor<?> ctor = testClass.getDeclaredConstructor(enclosing);
            ctor.setAccessible(true);
            return ctor.newInstance(enclosingInstance);
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

    private Method getCloneMethod(Class<?> c) {
        if (c == Object.class) return null;

        try {
            return c.getDeclaredMethod("clone");
        } catch (NoSuchMethodException e) {
            return getCloneMethod(c.getSuperclass());
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
                Method clone = getCloneMethod(fieldClass);
                if (clone == null) throw new RuntimeException("This cloneable class is not cloneable!");
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
    private Method match(Class<?> testClass, Class<? extends Annotation> annot_class, String sentenceSub) throws WordNotFoundException {
        for (Method meth : testClass.getDeclaredMethods()) {
            if (meth.isAnnotationPresent(annot_class)) {
                //check if the method has the right sentence
                try {
                    // extracting the value from the annotation's object
                    String value = (String) annot_class.getMethod("value").invoke(meth.getAnnotation(annot_class));
                    if (value.substring(0, value.lastIndexOf(" ")).equals(sentenceSub)) {
                        return meth;
                    }
                }
                catch(Exception ignored) {}
            }
        }
        Class<?> superc = testClass.getSuperclass();
        if (superc != null) {
            return match(superc, annot_class, sentenceSub);
        } else {
           //check which exception to throw
            throw newWordNotFoundException(annot_class.getSimpleName());
        }
    }
    @Override
    public void testOnInheritanceTree(String story, Class<?> testClass) throws Exception {
        if ((story == null) || testClass == null) throw new IllegalArgumentException();
        //initialize storyTestException values as local variables
        int numFailsLocal = 0;
        String firstFailedSentenceLocal = null;
        String expectedLocal = null;
        String resultLocal = null;
        Object testInstance = createTestInstance(testClass);
        boolean in_when = false;
        for (String sentence : story.split("\n")) {
            String[] words = sentence.split(" ", 2);
            String annotationName = words[0];
            Class<? extends Annotation> annotationClass = GetAnnotationClass(annotationName);
            String sentenceSub = words[1].substring(0, words[1].lastIndexOf(' ')); // Sentence without the parameter and annotation
            String parameter = sentence.substring(sentence.lastIndexOf(' ') + 1);
            Method method = match(testClass, annotationClass, sentenceSub);
            if (annotationName.equals("When") && !in_when) {
                backUpInstance(testInstance);
                in_when = true;
            }
            if (annotationName.equals("Then")) {
                in_when = false;
            }
            method.setAccessible(true);
            try {
                // check if a method takes parameter as string or integer
                if (method.getParameterTypes()[0].equals(String.class)) {
                    method.invoke(testInstance, parameter);
                } else {
                    method.invoke(testInstance, Integer.parseInt(parameter));
                }
            } catch (InvocationTargetException e) {
                numFailsLocal++;
                if (numFailsLocal == 1) {
                    restoreInstance(testInstance);
                    firstFailedSentenceLocal = sentence;
                    expectedLocal = ((ComparisonFailure) e.getCause()).getExpected();
                    resultLocal = ((ComparisonFailure) e.getCause()).getActual();
                }
            }
        }
        if (numFailsLocal > 0) {
            throw new StoryTestExceptionImpl(firstFailedSentenceLocal, expectedLocal, resultLocal, numFailsLocal);
        }
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