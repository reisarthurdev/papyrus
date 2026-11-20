plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.reisarthur.papyrus"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.reisarthur.papyrus"
        minSdk = 26
        targetSdk = 35
        // o GitHub Actions passa o número da execução: cada APK publicado é uma versão maior que a
        // anterior, então o Android instala por cima (atualiza) sem apagar os dados
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // chave fixa que fica no repositório: o APK de qualquer build (seu PC ou o GitHub)
        // tem a mesma assinatura, então um sempre atualiza o outro. A chave de debug padrão é
        // gerada por máquina, e uma assinatura diferente faz o Android recusar a atualização.
        getByName("debug") {
            storeFile = file("papyrus-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // se os segredos existirem no repositório, o release usa a chave própria (mais seguro)
        val ks = System.getenv("PAPYRUS_KEYSTORE_FILE")
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("PAPYRUS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PAPYRUS_KEY_ALIAS")
            keyPassword = System.getenv("PAPYRUS_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // o dicionário já vem compactado em blocos e é lido por posição direto do APK:
        // precisa ficar sem a compressão do APK. (as fontes, ao contrário, ficam menores comprimidas)
        noCompress += listOf("dic")
    }

    packaging {
        resources {
            // arquivos de licença/metadados das bibliotecas: não são usados pelo app em execução
            excludes += listOf(
                "META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin",
            )
        }
    }

    testOptions {
        // os testes de PDF rodam o pdfbox de verdade, que lê arquivos dos assets da biblioteca
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // o lint não barra o build do release (o app não depende de AndroidX/Play Store)
        checkReleaseBuilds = false
        abortOnError = false
    }

    // não embute no APK a lista de dependências (só serve para a Play Store)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Única dependência externa: extrair texto (com posição de cada letra) dos PDFs.
    // A renderização das páginas usa o PdfRenderer nativo do Android.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // só para os testes (não entram no APK)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// nos testes, mostra a saída e o erro completo no log do GitHub Actions
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "standard_out")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
