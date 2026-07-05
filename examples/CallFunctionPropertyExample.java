import java.util.List;

import com.opendaq.*;

/**
 * Call a device's function properties.  getPropertyValue returns a FUNC
 * property as a {@link FunctionObject}: call it with natural Java arguments
 * and the result comes back unboxed.
 */
public class CallFunctionPropertyExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        Device device = instance.addDevice("daqref://device0");

        FunctionObject sum = (FunctionObject) device.getPropertyValue("Protected.Sum");
        System.out.println("Protected.Sum(7, 5)   = " + sum.call(7, 5));
        System.out.println("Protected.Sum(40, 2)  = " + sum.call(40, 2));
        System.out.println("Protected.Sum(100, 1) = "
            + ((FunctionObject) device.getPropertyValue("Protected.Sum")).call(100, 1));

        FunctionObject sumList = (FunctionObject) device.getPropertyValue("Protected.SumList");
        System.out.println("Protected.SumList((1 2 3 4)) = " + sumList.call(List.of(1, 2, 3, 4)));
        System.out.println("Protected.SumList(()) = " + sumList.call(List.of()));

        try {
            sum.call(1, 2, 3);
            System.out.println("Unexpected: wrong arity was accepted.");
        } catch (RuntimeException e) {
            System.out.println();
            System.out.println("Wrong arity is rejected: " + e.getMessage());
        }
    }
}
