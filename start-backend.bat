@echo off
set ALI_BAI_LIAN_API_KEY=sk-f254db3d8e01446ea787181bcd669c0c
set TAVILY_API_KEY=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
set JAVA_HOME=E:\abjava
cd /d E:\nex-agent\super-agent\super-agent-business\super-agent-business-chat\target
E:\abjava\bin\java -jar super-agent-business-chat-0.0.1-SNAPSHOT.jar > E:\nex-agent\super-agent\app.log 2>&1
