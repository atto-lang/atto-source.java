package dev.oreslang.compiler;
import java.util.ArrayList;
import java.util.List;
public final class AotReflectionLimitProbe {
  public static void main(String[] args) {
    List<AotReflectionTableV1.ResolvedType> types=new ArrayList<>();
    for(int i=0;i<500;i++){
      List<AotReflectionTableV1.Field> fields=new ArrayList<>();
      for(int j=0;j<10;j++)fields.add(new AotReflectionTableV1.Field("field"+j,"X".repeat(500),AotReflectionTableV1.Visibility.PUBLIC));
      types.add(new AotReflectionTableV1.ResolvedType("pkg@revision","T"+i,List.of(),fields));
    }
    try {AotReflectionTableV1.encode(types);throw new AssertionError("oversized metadata accepted");}
    catch(IllegalArgumentException e) {
      if(!e.getMessage().contains("exceeds byte budget"))throw e;
      System.out.println("HOST_JDK21_AOT_DESCRIPTOR_OVERFLOW_REJECT_PASS");
    }
  }
}
