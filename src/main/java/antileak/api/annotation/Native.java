package antileak.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Native {
    Type type() default Type.STANDARD;

    public static enum Type {
        STANDARD,
        VMProtectBeginVirtualization,
        VMProtectBeginMutation,
        VMProtectBeginUltra;

        private static Type[] $values() {
            return new Type[]{
                    STANDARD,
                    VMProtectBeginVirtualization,
                    VMProtectBeginMutation,
                    VMProtectBeginUltra
            };
        }
    }
}
