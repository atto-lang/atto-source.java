package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static dev.oreslang.parser.Token.Type.*;

// Exact common-source #164 compiler-owned catalog, not runtime source loading.
final class MeasurePrelude {
    private MeasurePrelude() {}
    static final String SOURCE = "// Portable core reference prelude; file-local Verified Core candidate.\n// Import/export and generic refinements are not yet admitted by this profile.\ndefine dimension Length;\ndefine unit Meter: Length;\ndefine unit Millimeter: Length = Meter / 1000;\ndefine unit Centimeter: Length = Meter / 100;\ndefine unit Kilometer: Length = Meter * 1000;\ndefine unit Micrometer: Length = Meter / 1000000;\ndefine unit Nanometer: Length = Meter / 1000000000;\n// Complete SI decimal length prefix family; scales remain exact rational.\ndefine unit Decameter: Length = Meter * 10;\ndefine unit Hectometer: Length = Decameter * 10;\ndefine unit Megameter: Length = Kilometer * 1000;\ndefine unit Gigameter: Length = Megameter * 1000;\ndefine unit Terameter: Length = Gigameter * 1000;\ndefine unit Petameter: Length = Terameter * 1000;\ndefine unit Exameter: Length = Petameter * 1000;\ndefine unit Zettameter: Length = Exameter * 1000;\ndefine unit Yottameter: Length = Zettameter * 1000;\ndefine unit Ronnameter: Length = Yottameter * 1000;\ndefine unit Quettameter: Length = Ronnameter * 1000;\ndefine unit Decimeter: Length = Meter / 10;\ndefine unit Picometer: Length = Nanometer / 1000;\ndefine unit Femtometer: Length = Picometer / 1000;\ndefine unit Attometer: Length = Femtometer / 1000;\ndefine unit Zeptometer: Length = Attometer / 1000;\ndefine unit Yoctometer: Length = Zeptometer / 1000;\ndefine unit Rontometer: Length = Yoctometer / 1000;\ndefine unit Quectometer: Length = Rontometer / 1000;\ndefine dimension Time;\ndefine unit Second: Time;\ndefine unit Millis: Time = Second / 1000;\ndefine unit Micros: Time = Second / 1000000;\ndefine unit Nanos: Time = Second / 1000000000;\ndefine unit Minute: Time = Second * 60;\ndefine unit Hour: Time = Second * 3600;\ndefine unit Day: Time = Second * 86400;\ndefine dimension Mass;\ndefine unit Kilogram: Mass;\ndefine unit Gram: Mass = Kilogram / 1000;\ndefine unit Milligram: Mass = Gram / 1000;\ndefine dimension ElectricCurrent;\ndefine unit Ampere: ElectricCurrent;\ndefine unit Milliampere: ElectricCurrent = Ampere / 1000;\ndefine dimension Temperature;\n// Linear temperature differences only; absolute points are separately gated.\ndefine unit Kelvin: Temperature;\ndefine dimension AmountOfSubstance;\ndefine unit Mole: AmountOfSubstance;\ndefine dimension LuminousIntensity;\ndefine unit Candela: LuminousIntensity;\ndefine dimension Information;\ndefine unit Bit: Information;\ndefine unit Byte: Information = Bit * 8;\ndefine unit Kilobyte: Information = Byte * 1000;\ndefine unit Megabyte: Information = Byte * 1000000;\ndefine unit Gigabyte: Information = Byte * 1000000000;\ndefine unit Kibibyte: Information = Byte * 1024;\ndefine unit Mebibyte: Information = Byte * 1048576;\ndefine unit Gibibyte: Information = Byte * 1073741824;\n\ntype PositiveInt = int where self > 0;\ntype NegativeInt = int where self < 0;\ntype NonNegativeInt = int where self >= 0;\ntype NonPositiveInt = int where self <= 0;\ntype PositiveI32 = i32 where self > 0;\ntype NegativeI32 = i32 where self < 0;\ntype NonNegativeI32 = i32 where self >= 0;\ntype NonPositiveI32 = i32 where self <= 0;\n";
    private static final Set<String> UNITS = Set.of("Meter","Millimeter","Centimeter","Kilometer","Micrometer","Nanometer","Decameter","Hectometer","Megameter","Gigameter","Terameter","Petameter","Exameter","Zettameter","Yottameter","Ronnameter","Quettameter","Decimeter","Picometer","Femtometer","Attometer","Zeptometer","Yoctometer","Rontometer","Quectometer","Second","Millis","Micros","Nanos","Minute","Hour","Day","Kilogram","Gram","Milligram","Ampere","Milliampere","Kelvin","Mole","Candela","Bit","Byte","Kilobyte","Megabyte","Gigabyte","Kibibyte","Mebibyte","Gibibyte");
    private static final Set<String> ALIASES = Set.of("PositiveInt","NegativeInt","NonNegativeInt","NonPositiveInt","PositiveI32","NegativeI32","NonNegativeI32","NonPositiveI32");
    static boolean requested(List<Token> tokens) {
        if(tokens.size()<5 || tokens.get(0).type()!=PUB || tokens.get(1).type()!=FNC ||
           tokens.get(2).type()!=IDENT || !tokens.get(2).lexeme().equals("main") ||
           tokens.get(3).type()!=LPAREN) return false;
        for(int i=4;i+3<tokens.size();i++){
            var a=tokens.get(i);var b=tokens.get(i+1);var c=tokens.get(i+2);var d=tokens.get(i+3);
            if(a.type()==INT && b.type()==LT && c.type()==IDENT && d.type()==GT &&
               a.line()==b.line() && a.column()+a.lexeme().length()==b.column() && UNITS.contains(c.lexeme()))return true;
            if(a.type()==VAL && b.type()==IDENT &&
               ((ALIASES.contains(b.lexeme()) && c.type()==IDENT && d.type()==EQUAL) ||
                (c.type()==COLON && d.type()==IDENT && ALIASES.contains(d.lexeme()))))return true;
        }
        return false;
    }
    static List<Token> prepend(List<Token> user) {
        var catalog=new Lexer(SOURCE).scan();
        var result=new ArrayList<Token>(catalog.size()+user.size());
        result.addAll(catalog.subList(0,catalog.size()-1));
        result.addAll(user);
        return result;
    }
}
