# Cogni - O Leitor Universal

O **Cogni** é um aplicativo Android desenvolvido como parte de um **Projeto de Extensão Externa** do curso superior de **Análise e Desenvolvimento de Sistemas (TADS)** do **IFSP - Campus Campos do Jordão (CJO)**.

O objetivo do projeto é auxiliar pessoas com dificuldades de compreensão ou difilculdade visual, permitindo a leitura de textos físicos através da câmera e a simplificação desses textos utilizando Inteligência Artificial para tornar a informação mais acessível.

## 🚀 Funcionalidades

- **Reconhecimento de Texto (OCR):** Captura e identifica textos em tempo real utilizando a câmera do dispositivo.
- **Simplificação com IA:** Utiliza o modelo Gemini para reescrever textos complexos, tornando-os mais curtos, claros e fáceis de entender.
- **Leitura em Voz Alta (TTS):** Converte o texto (original ou simplificado) em áudio, com controle de velocidade (Lento, Médio, Rápido).
- **Interface Intuitiva:** Desenvolvida com Material Design 3 para garantir acessibilidade e facilidade de uso.

## 🛠️ Tecnologias Utilizadas

Este projeto foi construído utilizando as seguintes tecnologias e bibliotecas:

- **Linguagem:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)
- **Câmera:** [CameraX](https://developer.android.com/training/camerax) para captura de imagens.
- **OCR:** [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) para extração de texto de imagens.
- **Inteligência Artificial:** [Firebase AI (Gemini API)](https://firebase.google.com/docs/ai) para a simplificação de textos.
- **Voz:** [Android TextToSpeech (TTS)](https://developer.android.com/reference/android/speech/tts/TextToSpeech) para a síntese de voz.
- **Arquitetura:** Coroutines para operações assíncronas e Scaffold para estruturação de telas.

## 🎓 Contexto Acadêmico

Este software é um produto de extensão universitária do **IFSP-CJO**, reforçando o compromisso da instituição com a aplicação prática do conhecimento acadêmico em prol da comunidade e da inclusão social.