package io.github.nativegroup.spi.test;

import io.github.nativegroup.spi.NativeServiceLoader;
import org.junit.Test;

/**
 * test
 *
 * @author llnancy admin@lilu.org.cn
 * @since JDK8 2023/5/30
 */
public class TestMain {

    @Test
    public void test() {
        Language java = NativeServiceLoader.getNativeServiceLoader(Language.class).getNativeService("io.github.nativegroup.spi.test.JavaLanguage");
        java.hello();
        Language python = NativeServiceLoader.getNativeServiceLoader(Language.class).getNativeService("python");
        python.hello();
    }
}
