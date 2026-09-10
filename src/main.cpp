// llama.cpp - Local Inference Engine
// Modern chat UI with side drawer

#include "imgui.h"
#include "imgui_impl_android.h"
#include "imgui_impl_opengl3.h"
#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <vector>

#define LOG_TAG "llama.cpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static EGLDisplay           g_EglDisplay = EGL_NO_DISPLAY;
static EGLSurface           g_EglSurface = EGL_NO_SURFACE;
static EGLContext           g_EglContext = EGL_NO_CONTEXT;
static struct android_app*  g_App = nullptr;
static bool                 g_Initialized = false;
static std::string          g_IniFilename = "";

// Colors
static const ImU32 COL_BG        = IM_COL32(15, 15, 18, 255);
static const ImU32 COL_DRAWER    = IM_COL32(20, 20, 25, 255);
static const ImU32 COL_CARD      = IM_COL32(32, 32, 38, 255);
static const ImU32 COL_CARD_SEL  = IM_COL32(45, 45, 55, 255);
static const ImU32 COL_INPUT     = IM_COL32(38, 38, 44, 255);
static const ImU32 COL_BORDER    = IM_COL32(50, 50, 58, 255);
static const ImU32 COL_TEXT      = IM_COL32(235, 235, 240, 255);
static const ImU32 COL_TEXT_DIM  = IM_COL32(115, 115, 130, 255);
static const ImU32 COL_GREEN     = IM_COL32(76, 200, 100, 255);
static const ImU32 COL_YELLOW    = IM_COL32(230, 200, 50, 255);
static const ImU32 COL_BUBBLE_U  = IM_COL32(55, 55, 62, 255);
static const ImU32 COL_BUBBLE_A  = IM_COL32(30, 30, 36, 255);

// Model data
struct ModelInfo {
    const char* name;
    const char* category;
    const char* paramSize;
    const char* fileSize;
    const char* ram;
    bool downloaded;
    bool downloading;
    float progress;
};

static ModelInfo models[] = {
    { "Qwen2.5-0.5B-Instruct",      "Ultra Ringan",         "0.5B", "350-600MB",  "~1GB",  false, false, 0 },
    { "Llama-3.2-1B-Instruct",       "Ultra Ringan",         "1B",   "600-900MB",  "~1.5GB",false, false, 0 },
    { "Qwen2.5-1.5B-Instruct",       "Keseimbangan Terbaik", "1.5B", "900MB-1.2GB","~2GB",  false, false, 0 },
    { "Qwen2.5-Coder-1.5B-Instruct", "Spesialis Coding",     "1.5B", "~1.1GB",     "~2GB",  false, false, 0 },
    { "Llama-3.2-3B-Instruct",       "Keseimbangan Terbaik", "3B",   "1.8-2.2GB",  "~3GB",  false, false, 0 },
    { "Phi-3.5-mini-instruct",       "Ringan & Cepat",       "3.8B", "~2.2GB",     "~3.5GB",false, false, 0 },
};
static const int modelCount = 6;
static int selectedModel = 0;

// Chat
struct ChatMsg { std::string text; bool user; };
static std::vector<ChatMsg> chatHistory;
static char inputBuf[1024] = "";
static bool isGenerating = false;
static float genTimer = 0;

// UI state
static bool showDrawer = false;
static float drawerProgress = 0;

static const char* getResponse() {
    static int idx = 0;
    const char* r[] = {
        "Halo! Saya AI assistant yang berjalan di device Anda menggunakan llama.cpp. Siap membantu!",
        "Model ini menggunakan GGUF quantized weights (Q4_K_M) untuk performa optimal di mobile.",
        "Semua proses inference berjalan 100% offline. Tidak ada data yang dikirim ke server.",
        "Statistik: Engine llama.cpp v0.1.0 | UI Dear ImGui + OpenGL ES 3 | 100% C++ | APK ~3MB"
    };
    return r[idx++ % 4];
}

static void Init(struct android_app* app);
static void Shutdown();
static void MainLoopStep();

static void handleAppCmd(struct android_app* app, int32_t cmd) {
    if (cmd == APP_CMD_INIT_WINDOW) Init(app);
    else if (cmd == APP_CMD_TERM_WINDOW) Shutdown();
}
static int32_t handleInput(struct android_app* app, AInputEvent* e) {
    return ImGui_ImplAndroid_HandleInputEvent(e);
}

void android_main(struct android_app* app) {
    app->onAppCmd = handleAppCmd;
    app->onInputEvent = handleInput;
    chatHistory.push_back({"Hey! How can I help?", false});

    while (true) {
        int ev; struct android_poll_source* src;
        while (ALooper_pollOnce(g_Initialized ? 0 : -1, nullptr, &ev, (void**)&src) >= 0) {
            if (src) src->process(app, src);
            if (app->destroyRequested != 0) { if (!g_Initialized) Shutdown(); return; }
        }
        MainLoopStep();
    }
}

void Init(struct android_app* app) {
    if (g_Initialized) return;
    g_App = app;
    ANativeWindow_acquire(g_App->window);

    g_EglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(g_EglDisplay, 0, 0);
    const EGLint a[] = { EGL_BLUE_SIZE,8,EGL_GREEN_SIZE,8,EGL_RED_SIZE,8,EGL_DEPTH_SIZE,24,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_NONE };
    EGLint n; eglChooseConfig(g_EglDisplay,a,nullptr,0,&n);
    EGLConfig c; eglChooseConfig(g_EglDisplay,a,&c,1,&n);
    EGLint f; eglGetConfigAttrib(g_EglDisplay,c,EGL_NATIVE_VISUAL_ID,&f);
    ANativeWindow_setBuffersGeometry(g_App->window,0,0,f);
    const EGLint ctx[] = { EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE };
    g_EglContext = eglCreateContext(g_EglDisplay,c,EGL_NO_CONTEXT,ctx);
    g_EglSurface = eglCreateWindowSurface(g_EglDisplay,c,g_App->window,nullptr);
    eglMakeCurrent(g_EglDisplay,g_EglSurface,g_EglSurface,g_EglContext);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    g_IniFilename = std::string(app->activity->internalDataPath) + "/imgui.ini";
    io.IniFilename = g_IniFilename.c_str();

    ImGui::StyleColorsDark();
    ImGuiStyle& s = ImGui::GetStyle();
    float sc = 2.5f;
    s.ScaleAllSizes(sc);
    s.FontScaleDpi = sc;
    s.FrameRounding = 12;
    s.GrabRounding = 12;
    s.ItemSpacing = ImVec2(10,10);
    s.WindowPadding = ImVec2(0,0);
    s.Colors[ImGuiCol_WindowBg] = ImVec4(0.06f,0.06f,0.07f,1);
    s.Colors[ImGuiCol_FrameBg] = ImVec4(0.15f,0.15f,0.17f,1);
    s.Colors[ImGuiCol_Button] = ImVec4(0.13f,0.13f,0.15f,1);
    s.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.20f,0.20f,0.23f,1);
    s.Colors[ImGuiCol_Text] = ImVec4(0.92f,0.92f,0.94f,1);
    s.Colors[ImGuiCol_TextDisabled] = ImVec4(0.45f,0.45f,0.50f,1);
    s.Colors[ImGuiCol_ScrollbarBg] = ImVec4(0,0,0,0);
    s.Colors[ImGuiCol_ScrollbarGrab] = ImVec4(0.20f,0.20f,0.23f,1);

    ImGui_ImplAndroid_Init(g_App->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");
    g_Initialized = true;
}

void MainLoopStep() {
    ImGuiIO& io = ImGui::GetIO();
    if (g_EglDisplay == EGL_NO_DISPLAY) return;

    if (isGenerating) {
        genTimer += io.DeltaTime;
        if (genTimer > 1.5f) {
            isGenerating = false;
            genTimer = 0;
            chatHistory.push_back({getResponse(), false});
        }
    }

    // Animate drawer
    float target = showDrawer ? 1.0f : 0.0f;
    drawerProgress += (target - drawerProgress) * 0.15f;
    if (drawerProgress < 0.01f) drawerProgress = 0;
    if (drawerProgress > 0.99f) drawerProgress = 1;

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();

    float W = io.DisplaySize.x;
    float H = io.DisplaySize.y;

    ImGui::SetNextWindowPos(ImVec2(0,0));
    ImGui::SetNextWindowSize(ImVec2(W,H));
    ImGui::Begin("##Main", nullptr,
        ImGuiWindowFlags_NoTitleBar|ImGuiWindowFlags_NoResize|ImGuiWindowFlags_NoMove|
        ImGuiWindowFlags_NoScrollbar|ImGuiWindowFlags_NoCollapse);

    ImDrawList* dl = ImGui::GetWindowDrawList();

    // Dark overlay when drawer open
    if (drawerProgress > 0.01f) {
        ImU32 overlay = IM_COL32(0,0,0,(int)(150 * drawerProgress));
        dl->AddRectFilled(ImVec2(0,0), ImVec2(W,H), overlay);
    }

    // Drawer
    if (drawerProgress > 0.01f) {
        float dw = W * 0.78f;
        float dx = -dw * (1.0f - drawerProgress);
        dl->AddRectFilled(ImVec2(dx, 0), ImVec2(dx + dw, H), COL_DRAWER, 0);

        float pad = 20;
        float y = 50;

        // Title
        dl->AddText(ImVec2(dx + pad, y), COL_TEXT, "Models");
        y += 45;

        // Close button
        ImGui::SetCursorPos(ImVec2(dx + pad, y));
        if (ImGui::Button("X", ImVec2(dw - pad*2, 38))) showDrawer = false;
        y += 50;

        // Model list
        float listH = H - y - 60;
        ImGui::SetCursorPos(ImVec2(dx, y));
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0,0));
        ImGui::BeginChild("##mlist", ImVec2(dw, listH), ImGuiChildFlags_None);

        float cy = 0;
        for (int i = 0; i < modelCount; i++) {
            ModelInfo& m = models[i];
            bool sel = (selectedModel == i);

            // Category
            if (i == 0 || strcmp(m.category, models[i-1].category) != 0) {
                ImGui::SetCursorPos(ImVec2(pad, cy));
                ImGui::TextColored(ImVec4(0.45f,0.45f,0.50f,1), "%s", m.category);
                cy += 28;
            }

            // Card
            ImGui::SetCursorPos(ImVec2(pad, cy));
            ImGui::PushStyleColor(ImGuiCol_ChildBg, sel ? ImVec4(0.18f,0.18f,0.22f,1) : ImVec4(0.13f,0.13f,0.15f,1));
            ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 10);
            ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(12,10));

            char cid[16]; snprintf(cid,16,"##mc%d",i);
            ImGui::BeginChild(cid, ImVec2(dw - pad*2, 65), ImGuiChildFlags_None);

            ImGui::TextColored(sel ? ImVec4(0.92f,0.92f,0.94f,1) : ImVec4(0.65f,0.65f,0.70f,1), "%s", m.name);
            ImGui::TextColored(ImVec4(0.45f,0.45f,0.50f,1), "%s | %s", m.paramSize, m.fileSize);

            // Status
            if (m.downloaded) {
                ImGui::SameLine(dw - pad*2 - 60);
                ImGui::TextColored(ImVec4(0.30f,0.78f,0.40f,1), "Ready");
            } else if (m.downloading) {
                ImGui::SameLine(dw - pad*2 - 60);
                ImGui::TextColored(ImVec4(0.90f,0.78f,0.20f,1), "%.0f%%", m.progress);
            } else {
                ImGui::SameLine(dw - pad*2 - 70);
                if (ImGui::SmallButton("Get")) { m.downloading = true; m.progress = 0; }
            }

            ImGui::EndChild();
            ImGui::PopStyleVar(2);
            ImGui::PopStyleColor();

            if (ImGui::IsItemClicked()) { selectedModel = i; showDrawer = false; }
            cy += 72;
        }

        ImGui::EndChild();
        ImGui::PopStyleVar();

        // Footer
        dl->AddText(ImVec2(dx + pad, H - 45), COL_TEXT_DIM, "llama.cpp v0.1.0");
    }

    // === CHAT AREA ===
    float topH = 60;
    float inputH = 70;
    float chatH = H - topH - inputH;

    // Top bar
    ImGui::SetCursorPos(ImVec2(12, 12));
    if (ImGui::Button("#", ImVec2(38,38))) showDrawer = !showDrawer;
    ImGui::SameLine();
    ImGui::SetCursorPosX(W/2 - 80);
    ImGui::TextColored(ImVec4(0.55f,0.55f,0.60f,1), "%s", models[selectedModel].name);
    ImGui::SameLine(W - 50);
    if (ImGui::Button("+", ImVec2(38,38))) {
        chatHistory.clear();
        chatHistory.push_back({"Hey! How can I help?", false});
    }

    // Messages
    ImGui::SetCursorPos(ImVec2(0, topH));
    ImGui::BeginChild("##chat", ImVec2(W, chatH), ImGuiChildFlags_None);

    float mp = 20;
    float maxBubW = W * 0.78f;
    float y = 10;

    for (size_t i = 0; i < chatHistory.size(); i++) {
        auto& msg = chatHistory[i];
        float tw = ImGui::CalcTextSize(msg.text.c_str()).x;
        if (tw > maxBubW - 24) tw = maxBubW - 24;
        float bw = tw + 24;

        if (msg.user) {
            float bx = W - mp - bw;
            dl->AddRectFilled(ImVec2(bx, y), ImVec2(bx + bw, y + 38), COL_BUBBLE_U, 14);
            dl->AddText(ImVec2(bx + 12, y + 9), COL_TEXT, msg.text.c_str());
            y += 48;
        } else {
            dl->AddRectFilled(ImVec2(mp, y), ImVec2(mp + bw, y + 38), COL_BUBBLE_A, 14);
            dl->AddText(ImVec2(mp + 12, y + 9), COL_TEXT, msg.text.c_str());
            y += 44;

            // Action icons
            const char* icons[] = {"C","S","P","L","D","R"};
            float ix = mp;
            for (int b = 0; b < 6; b++) {
                dl->AddText(ImVec2(ix + 2, y + 2), COL_TEXT_DIM, icons[b]);
                ix += 26;
            }
            y += 28;
        }
    }

    // Typing indicator
    if (isGenerating) {
        float t = fmodf((float)ImGui::GetTime() * 3.0f, 3.0f);
        int d = ((int)t % 3) + 1;
        char dots[8] = "";
        for (int j = 0; j < d; j++) strcat(dots, ".");
        dl->AddRectFilled(ImVec2(mp, y), ImVec2(mp + 70, y + 38), COL_BUBBLE_A, 14);
        dl->AddText(ImVec2(mp + 12, y + 9), COL_TEXT_DIM, dots);
    }

    ImGui::SetScrollHereY(1.0f);
    ImGui::EndChild();

    // === INPUT BAR ===
    float barY = H - inputH;
    dl->AddRectFilled(ImVec2(0, barY), ImVec2(W, H), COL_BG);
    dl->AddLine(ImVec2(0, barY), ImVec2(W, barY), COL_BORDER);

    // Plus
    ImGui::SetCursorPos(ImVec2(10, barY + 14));
    ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.13f,0.13f,0.15f,1));
    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.92f,0.92f,0.94f,1));
    ImGui::Button("+", ImVec2(42, 42));
    ImGui::PopStyleColor(2);

    // Input
    ImGui::SetCursorPos(ImVec2(60, barY + 12));
    ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, 20);
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(16,12));
    ImGui::PushItemWidth(W - 126);
    ImGui::InputText("##inp", inputBuf, sizeof(inputBuf));
    ImGui::PopItemWidth();
    ImGui::PopStyleVar(2);

    // Send
    ImGui::SetCursorPos(ImVec2(W - 54, barY + 14));
    bool canSend = inputBuf[0] && !isGenerating;
    if (canSend) {
        ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.93f,0.93f,0.93f,1));
        ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.06f,0.06f,0.06f,1));
    } else {
        ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.13f,0.13f,0.15f,1));
        ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.45f,0.45f,0.50f,1));
    }
    if (ImGui::Button(">", ImVec2(42,42)) && canSend) {
        chatHistory.push_back({inputBuf, true});
        isGenerating = true;
        genTimer = 0;
        inputBuf[0] = '\0';
    }
    ImGui::PopStyleColor(2);

    ImGui::End();

    ImGui::Render();
    glViewport(0,0,(int)W,(int)H);
    glClearColor(0.06f,0.06f,0.07f,1);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(g_EglDisplay, g_EglSurface);
}

void Shutdown() {
    if (!g_Initialized) return;
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();
    if (g_EglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_EglDisplay,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        if (g_EglContext != EGL_NO_CONTEXT) eglDestroyContext(g_EglDisplay,g_EglContext);
        if (g_EglSurface != EGL_NO_SURFACE) eglDestroySurface(g_EglDisplay,g_EglSurface);
        eglTerminate(g_EglDisplay);
    }
    g_EglDisplay=EGL_NO_DISPLAY; g_EglContext=EGL_NO_CONTEXT; g_EglSurface=EGL_NO_SURFACE;
    ANativeWindow_release(g_App->window);
    g_Initialized = false;
}
