FROM mcr.microsoft.com/dotnet/sdk:10.0 AS builder
ARG FRAMEWORK
WORKDIR /app
COPY dotnet/${FRAMEWORK}/ .
RUN dotnet publish -c Release -o /out -p:PublishAot=false
# Find the entry point DLL (the one with .deps.json)
RUN basename $(ls /out/*.deps.json | head -1) .deps.json > /out/_entrypoint

FROM mcr.microsoft.com/dotnet/aspnet:10.0
COPY --from=builder /out /app
WORKDIR /app
ENV ASPNETCORE_URLS=http://+:8080
ENV ASPNETCORE_ENVIRONMENT=Production
ENV DOTNET_ENVIRONMENT=Production
ENTRYPOINT ["sh", "-c", "dotnet /app/$(cat /app/_entrypoint).dll"]
