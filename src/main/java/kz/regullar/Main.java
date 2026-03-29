package kz.regullar;

import kz.regullar.core.Core;
import kz.regullar.transformer.impl.packaging.RedHerring;
import kz.regullar.util.SimpleNameFactory;
import kz.regullar.transformer.impl.string.*;
import kz.regullar.transformer.impl.flow.*;
import kz.regullar.transformer.impl.number.*;
import kz.regullar.transformer.impl.other.*;
import kz.regullar.transformer.impl.tricks.*;
import kz.regullar.transformer.impl.optimization.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * correct obf transformer list
 *
 * optimizer
 * firstflow
 * firstnumber
 * parametrhider
 * stringobf
 * shuffle
 * signature
 * flagconfuser
 */

public class Main {
    static SimpleNameFactory simpleNameFactory = new SimpleNameFactory(true);

    public static void main(String[] args) {
        Path inputPath = Paths.get("input.jar");
        Path outputPath = Paths.get("output.jar");
        System.out.println("Начинаем обфускацию: " + inputPath);

        try {
            Core obfuscator = Core.builder()
                    .input(inputPath)
                    .output(outputPath)
                    .nameFactory(simpleNameFactory)
                    .blacklist(
                            "net/minecraft/",
                            "it/",
                            "io/",
                            "joptsimple/",
                            "javax/",
                            "org/",
                            "lombok/",
                            "us/",
                            "kotlin/",
                            "oshi/",
                            "okio/",
                            "lib/",
                            "okhttp3/",
                            "net/optifine/shaders/",
                            "baritone/",
                            "com/",
                            "thunder/hack/obf/",
                            "thunder/hack/injection/",
                            "dev/luvbeeq/",
                            "dev/wh1tew1ndows/sb.class",
//                            "fcked/by/regullar/elw.class",
                            "dev/wh1tew1ndows/client/utils/render/shader/glsl/omg133.class",
                            "dev/uy.class",
//                            "fcked/by/regullar/hac.class",
                            "tq/monte/a/",
                            "kz/regullar/optmedia/",
                            "ru/hogoshi/",
                            "what/ket4upinka/visuals/mixins/",
                            "dev/ataevskiyvisuals/obf/",
                            "ru/client/gb.class",
                            "fcked/by/regullar/aaW.class",
                            "ru/client/a/",
                            "ru/aurora/a/",
                            "fun/nexisdlc/a/"
//                            "fcked/by/regullar/gpw.class"
                    )
                    .register(new Optimizer())
                    .register(new FirstControlFlowTransformer())
//                    .register(new CodexFlowTransformer())
//                    .register(new FifthControlFlowTransformer())
//                    .register(new FourthControlFlowTransformer())
//                    .register(new SecondControlFlowTransformer())
//                    .register(new SecondControlFlowTransformer())
                    .register(new FirstNumberTransformer())
//                    .register(new ParameterHideTransformer())
                    .register(new StringObfuscatingTransformer())
//                    .register(new DecompilerCrasher())
                    .register(new ShuffleTransformer())
                    .register(new SignatureChangerTransformer())
//                    .register(new AntiPromptTransformer())
//                    .register(new FlagConfuseTransformer())
                    .register(new RedHerring())
//                    .register(new StaticInitializionTransformer())


                    .build();
            obfuscator.run();

            System.out.println("Обфускация завершена. Результат сохранен в: " + outputPath);

        } catch (IOException e) {
            System.err.println("Ошибка во время обфускации:");
            e.printStackTrace();
        }
    }
}