package ms.imf.redpoint.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.ANNOTATION_TYPE)
public @interface SubNode2 {

    /**
     * @see SubNode#type()
     */
    String type();

    /**
     * @see SubNode#args()
     */
    NodeArg[] args() default {};

    /**
     * @see SubNode#subNodes()
     */
    SubNode3[] subNodes() default {};

    /**
     * @see SubNode#subRef()
     */
    Class subRef() default Void.class;
}
