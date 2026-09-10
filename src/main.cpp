// IronAI - Local AI Inference Engine
// Built with Dear ImGui + Android NativeActivity + OpenGL ES 3

#include "imgui.h"
#include "imgui_impl_android.h"
#include "imgui_impl_opengl3.h"
#include <android/log.h>
#include <android_native_app_glue.h>
#include <android/asset_manager.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <cstdlib>

#define LOG_TAG "IronAI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static EGLDisplay           g_EglDisplay = EGL_NO_DISPLAY;
static EGLSurface           g_EglSurface = EGL_NO_SURFACE;
static EGLContext           g_EglContext = EGL_NO_CONTEXT;
static struct android_app*  g_App = nullptr;
static bool                 g_Initialized = false;
static std::string          g_IniFilename = "";

static char promptBuffer[1024] = "";
static char outputBuffer[4096] = "";
static int selectedModel = 0;
static float temperature = 0.7f;
static int maxTokens = 256;
static bool isGenerating = false;
static float generateTimer = 0.0f;
static int selectedTab = 0;

static const char* modelNames[] = {
    "IronAI-1B (Tiny)",
    "IronAI-3B (Base)",
    "IronAI-7B (Medium)",
    "IronAI-13B (Large)",
    "IronAI-70B (Huge)"
};
static const int modelCount = 5;

static const char* dummyResponses[] = {
    "Halo! Saya adalah AI assistant yang berjalan di perangkat Anda. "
    "Ini adalah versi demo. Fitur yang direncanakan:\n\n"
    "- Chat & Conversation\n"
    "- Text Generation\n"
    "- Code Assistance\n"
    "- Creative Writing",

    "Pertanyaan menarik! Dalam versi production, model ini akan "
    "menggunakan arsitektur Transformer dengan optimasi untuk mobile device. "
    "Inference akan dilakukan menggunakan quantized weights (INT4/INT8).",

    "Berikut adalah contoh respons AI:\n\n"
    "The quick brown fox jumps over the lazy dog.\n"
    "Pack my box with five dozen liquor jugs.\n\n"
    "Kalimat-kalimat di atas adalah pangram.",

    "Statistik Device:\n\n"
    "- Model: IronAI-7B (Demo)\n"
    "- Platform: Android (NativeActivity)\n"
    "- UI: Dear ImGui + OpenGL ES 3\n"
    "- Backend: 100% C++\n"
    "- APK Size: ~3 MB"
};

static void Init(struct android_app* app);
static void Shutdown();
static void MainLoopStep();
static int ShowSoftKeyboardInput();
static int PollUnicodeChars();

static void handleAppCmd(struct android_app* app, int32_t appCmd)
{
    switch (appCmd)
    {
    case APP_CMD_INIT_WINDOW:
        Init(app);
        break;
    case APP_CMD_TERM_WINDOW:
        Shutdown();
        break;
    default:
        break;
    }
}

static int32_t handleInputEvent(struct android_app* app, AInputEvent* inputEvent)
{
    return ImGui_ImplAndroid_HandleInputEvent(inputEvent);
}

void android_main(struct android_app* app)
{
    app->onAppCmd = handleAppCmd;
    app->onInputEvent = handleInputEvent;

    while (true)
    {
        int out_events;
        struct android_poll_source* out_data;

        while (ALooper_pollOnce(g_Initialized ? 0 : -1, nullptr, &out_events, (void**)&out_data) >= 0)
        {
            if (out_data != nullptr)
                out_data->process(app, out_data);

            if (app->destroyRequested != 0)
            {
                if (!g_Initialized)
                    Shutdown();
                return;
            }
        }

        MainLoopStep();
    }
}

void Init(struct android_app* app)
{
    if (g_Initialized)
        return;

    g_App = app;
    ANativeWindow_acquire(g_App->window);

    {
        g_EglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_EglDisplay == EGL_NO_DISPLAY)
            LOGE("eglGetDisplay returned EGL_NO_DISPLAY");

        if (eglInitialize(g_EglDisplay, 0, 0) != EGL_TRUE)
            LOGE("eglInitialize returned error");

        const EGLint egl_attributes[] = {
            EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8,
            EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_NONE
        };
        EGLint num_configs = 0;
        eglChooseConfig(g_EglDisplay, egl_attributes, nullptr, 0, &num_configs);

        EGLConfig egl_config;
        eglChooseConfig(g_EglDisplay, egl_attributes, &egl_config, 1, &num_configs);
        EGLint egl_format;
        eglGetConfigAttrib(g_EglDisplay, egl_config, EGL_NATIVE_VISUAL_ID, &egl_format);
        ANativeWindow_setBuffersGeometry(g_App->window, 0, 0, egl_format);

        const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        g_EglContext = eglCreateContext(g_EglDisplay, egl_config, EGL_NO_CONTEXT, egl_context_attributes);

        g_EglSurface = eglCreateWindowSurface(g_EglDisplay, egl_config, g_App->window, nullptr);
        eglMakeCurrent(g_EglDisplay, g_EglSurface, g_EglSurface, g_EglContext);
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();

    g_IniFilename = std::string(app->activity->internalDataPath) + "/imgui.ini";
    io.IniFilename = g_IniFilename.c_str();

    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();

    float main_scale = 2.5f;
    style.ScaleAllSizes(main_scale);
    style.FontScaleDpi = main_scale;
    style.WindowRounding = 12.0f;
    style.FrameRounding = 8.0f;
    style.GrabRounding = 8.0f;

    // Dark theme colors
    ImVec4* c = style.Colors;
    c[ImGuiCol_WindowBg]           = ImVec4(0.07f, 0.07f, 0.09f, 1.00f);
    c[ImGuiCol_ChildBg]            = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_PopupBg]            = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_Border]             = ImVec4(0.22f, 0.22f, 0.27f, 1.00f);
    c[ImGuiCol_FrameBg]            = ImVec4(0.15f, 0.15f, 0.20f, 1.00f);
    c[ImGuiCol_FrameBgHovered]     = ImVec4(0.22f, 0.22f, 0.27f, 1.00f);
    c[ImGuiCol_FrameBgActive]      = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_TitleBg]            = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_TitleBgActive]      = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_ScrollbarBg]        = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_ScrollbarGrab]      = ImVec4(0.22f, 0.22f, 0.27f, 1.00f);
    c[ImGuiCol_ScrollbarGrabHovered] = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_ScrollbarGrabActive] = ImVec4(0.00f, 0.82f, 0.73f, 1.00f);
    c[ImGuiCol_Button]             = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_ButtonHovered]      = ImVec4(0.00f, 0.82f, 0.73f, 1.00f);
    c[ImGuiCol_ButtonActive]       = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_Header]             = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_HeaderHovered]      = ImVec4(0.00f, 0.82f, 0.73f, 1.00f);
    c[ImGuiCol_HeaderActive]       = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_Text]               = ImVec4(0.90f, 0.90f, 0.94f, 1.00f);
    c[ImGuiCol_TextDisabled]       = ImVec4(0.55f, 0.55f, 0.63f, 1.00f);
    c[ImGuiCol_Tab]                = ImVec4(0.11f, 0.11f, 0.15f, 1.00f);
    c[ImGuiCol_TabHovered]         = ImVec4(0.00f, 0.82f, 0.73f, 1.00f);
    c[ImGuiCol_TabSelected]        = ImVec4(0.00f, 0.71f, 0.63f, 1.00f);
    c[ImGuiCol_Separator]          = ImVec4(0.22f, 0.22f, 0.27f, 1.00f);

    ImGui_ImplAndroid_Init(g_App->window);
    ImGui_ImplOpenGL3_Init("#version 300 es");

    g_Initialized = true;
    LOGI("IronAI initialized successfully");
}

void MainLoopStep()
{
    ImGuiIO& io = ImGui::GetIO();
    if (g_EglDisplay == EGL_NO_DISPLAY)
        return;

    PollUnicodeChars();

    static bool WantTextInputLast = false;
    if (io.WantTextInput && !WantTextInputLast)
        ShowSoftKeyboardInput();
    WantTextInputLast = io.WantTextInput;

    float dt = io.DeltaTime;
    if (isGenerating) {
        generateTimer += dt;
        if (generateTimer > 2.0f) {
            isGenerating = false;
            generateTimer = 0.0f;
            int idx = rand() % 4;
            strncpy(outputBuffer, dummyResponses[idx], sizeof(outputBuffer) - 1);
        }
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();

    float W = io.DisplaySize.x;
    float H = io.DisplaySize.y;
    float pad = 20.0f;
    float contentW = W - pad * 2.0f;

    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(ImVec2(W, H));
    ImGuiWindowFlags flags = ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                             ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoScrollbar |
                             ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoBackground;

    if (!ImGui::Begin("##Main", nullptr, flags))
    {
        ImGui::End();
        ImGui::Render();
        glViewport(0, 0, (int)W, (int)H);
        glClearColor(0.07f, 0.07f, 0.09f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
        eglSwapBuffers(g_EglDisplay, g_EglSurface);
        return;
    }

    // Status bar
    ImGui::SetCursorPosX(pad);
    if (isGenerating) {
        float t = fmodf((float)ImGui::GetTime() * 3.0f, 3.0f);
        int dots = ((int)t % 3) + 1;
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Generating %.*s", dots, "...");
    } else {
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Ready");
    }
    ImGui::SameLine(contentW - 80.0f);
    ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "v0.1.0");

    ImGui::SetCursorPosX(pad);
    ImGui::Spacing();

    // Header
    ImGui::SetCursorPosX(pad);
    ImGui::TextColored(ImVec4(0.00f, 0.71f, 0.63f, 1.0f), "[AI]");
    ImGui::SameLine();
    ImGui::TextColored(ImVec4(0.90f, 0.90f, 0.94f, 1.0f), "IronAI");

    ImGui::SetCursorPosX(pad);
    ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Local AI Inference Engine");

    ImGui::SetCursorPosX(pad);
    ImGui::Spacing();

    // Tab bar
    ImGui::SetCursorPosX(pad);
    if (ImGui::BeginTabBar("##Tabs", ImGuiTabBarFlags_None))
    {
        if (ImGui::BeginTabItem("Chat"))
        {
            selectedTab = 0;
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Settings"))
        {
            selectedTab = 1;
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("About"))
        {
            selectedTab = 2;
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }

    ImGui::Spacing();
    ImGui::SetCursorPosX(pad);

    // Tab content
    if (selectedTab == 0)
    {
        // --- CHAT ---
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Model");
        ImGui::SetCursorPosX(pad);
        ImGui::PushItemWidth(contentW);
        if (ImGui::BeginCombo("##model", modelNames[selectedModel]))
        {
            for (int i = 0; i < modelCount; i++)
            {
                bool is_selected = (selectedModel == i);
                if (ImGui::Selectable(modelNames[i], is_selected))
                    selectedModel = i;
                if (is_selected)
                    ImGui::SetItemDefaultFocus();
            }
            ImGui::EndCombo();
        }
        ImGui::PopItemWidth();

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Prompt");
        ImGui::SetCursorPosX(pad);
        ImGui::PushItemWidth(contentW);
        ImGui::InputTextMultiline("##prompt", promptBuffer, sizeof(promptBuffer),
                                  ImVec2(contentW, 100.0f));
        ImGui::PopItemWidth();

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        if (isGenerating)
        {
            ImGui::BeginDisabled();
            ImGui::Button("Generating...", ImVec2(contentW, 45.0f));
            ImGui::EndDisabled();
        }
        else
        {
            if (ImGui::Button("Generate Response", ImVec2(contentW, 45.0f)))
            {
                if (promptBuffer[0] != '\0')
                {
                    isGenerating = true;
                    generateTimer = 0.0f;
                    outputBuffer[0] = '\0';
                }
            }
        }

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        float btnW = (contentW - 16.0f) / 3.0f;
        if (ImGui::Button("Clear", ImVec2(btnW, 30.0f)))
        {
            promptBuffer[0] = '\0';
            outputBuffer[0] = '\0';
        }
        ImGui::SameLine();
        ImGui::Button("Copy", ImVec2(btnW, 30.0f));
        ImGui::SameLine();
        ImGui::Button("Share", ImVec2(btnW, 30.0f));

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Response");
        ImGui::SetCursorPosX(pad);
        ImGui::BeginChild("##output", ImVec2(contentW, 180.0f), ImGuiChildFlags_Borders);
        if (outputBuffer[0] != '\0')
        {
            ImGui::TextWrapped("%s", outputBuffer);
        }
        else if (isGenerating)
        {
            float t = fmodf((float)ImGui::GetTime() * 3.0f, 3.0f);
            int dots = ((int)t % 3) + 1;
            ImGui::TextColored(ImVec4(0.00f, 0.71f, 0.63f, 1.0f), "Thinking %.*s", dots, "...");
        }
        else
        {
            ImGui::TextDisabled("Send a prompt to get started");
        }
        ImGui::EndChild();
    }
    else if (selectedTab == 1)
    {
        // --- SETTINGS ---
        ImGui::TextColored(ImVec4(0.90f, 0.90f, 0.94f, 1.0f), "Temperature");
        ImGui::SetCursorPosX(pad);
        ImGui::PushItemWidth(contentW);
        ImGui::SliderFloat("##temp", &temperature, 0.0f, 2.0f, "%.2f");
        ImGui::PopItemWidth();

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.90f, 0.90f, 0.94f, 1.0f), "Max Tokens");
        ImGui::SetCursorPosX(pad);
        ImGui::PushItemWidth(contentW);
        ImGui::SliderInt("##tokens", &maxTokens, 64, 2048, "%d");
        ImGui::PopItemWidth();

        ImGui::Spacing();
        ImGui::Separator();
        ImGui::Spacing();

        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.90f, 0.90f, 0.94f, 1.0f), "Device Info");
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Platform: NativeActivity + OpenGL ES 3");
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Backend: 100%% C++");
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "UI: Dear ImGui");
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "APK Size: ~3 MB");
    }
    else
    {
        // --- ABOUT ---
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.00f, 0.71f, 0.63f, 1.0f), "[AI]");

        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.90f, 0.90f, 0.94f, 1.0f), "IronAI");

        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Local AI Inference Engine");

        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f), "Version 0.1.0 (Demo)");

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::Separator();
        ImGui::Spacing();

        ImGui::SetCursorPosX(pad);
        ImGui::PushTextWrapPos(pad + contentW);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f),
            "IronAI adalah aplikasi inference AI yang dirancang untuk berjalan "
            "sepenuhnya di perangkat mobile tanpa koneksi internet.");
        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f),
            "Menggunakan NativeActivity + Dear ImGui untuk performa maksimal "
            "dan ukuran APK yang sangat ringan (~3 MB).");
        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.55f, 0.55f, 0.63f, 1.0f),
            "Ditulis 100%% dalam C++ murni.");
        ImGui::PopTextWrapPos();

        ImGui::Spacing();
        ImGui::SetCursorPosX(pad);
        ImGui::TextColored(ImVec4(0.00f, 0.71f, 0.63f, 1.0f), "github.com/ironai");
    }

    ImGui::End(); // Main window

    ImGui::Render();
    glViewport(0, 0, (int)W, (int)H);
    glClearColor(0.07f, 0.07f, 0.09f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(g_EglDisplay, g_EglSurface);
}

void Shutdown()
{
    if (!g_Initialized)
        return;

    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplAndroid_Shutdown();
    ImGui::DestroyContext();

    if (g_EglDisplay != EGL_NO_DISPLAY)
    {
        eglMakeCurrent(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_EglContext != EGL_NO_CONTEXT)
            eglDestroyContext(g_EglDisplay, g_EglContext);
        if (g_EglSurface != EGL_NO_SURFACE)
            eglDestroySurface(g_EglDisplay, g_EglSurface);
        eglTerminate(g_EglDisplay);
    }

    g_EglDisplay = EGL_NO_DISPLAY;
    g_EglContext = EGL_NO_CONTEXT;
    g_EglSurface = EGL_NO_SURFACE;
    ANativeWindow_release(g_App->window);
    g_Initialized = false;
    LOGI("IronAI shut down");
}

static int ShowSoftKeyboardInput()
{
    return 0;
}

static int PollUnicodeChars()
{
    return 0;
}
