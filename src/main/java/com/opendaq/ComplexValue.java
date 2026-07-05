package com.opendaq;

/**
 * The natural Java value of an openDAQ ComplexNumber (Java has no built-in
 * complex type).
 */
public record ComplexValue(double real, double imaginary) {

    public static ComplexValue of(double real, double imaginary) {
        return new ComplexValue(real, imaginary);
    }

    @Override
    public String toString() {
        return imaginary < 0 ? real + "" + imaginary + "i" : real + "+" + imaginary + "i";
    }
}
