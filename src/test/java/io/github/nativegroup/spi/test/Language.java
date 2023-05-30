package io.github.nativegroup.spi.test;

import io.github.nativegroup.spi.SPI;

/**
 * native service
 *
 * @author llnancy admin@lilu.org.cn
 * @since JDK8 2023/5/30
 */
@SPI
public interface Language {

    default void hello() {
        System.out.println(this.getClass().getName());
    }
}
