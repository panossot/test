package io.undertow;

import org.junit.Test;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public class TestNULTestCase  {

    @Test
    public void testNUL_() throws FileNotFoundException {
        new FileOutputStream("NUL:");
    }
    
    @Test
    public void testNUL() throws FileNotFoundException {
        new FileOutputStream("NUL");
    }

}
