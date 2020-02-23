SHELL=/bin/bash

CURRENT_TIME = $(shell date)

.EXPORT_ALL_VARIABLES:

.PHONY:	clean repl run javac jar uberjar install deploy help

YELLOW_PRINT = \033[0;33m
GREEN_PRINT = \033[0;92m
NORMAL_TEXT = \033[0m

define cecho
	@echo '------------------------------------------'
	@echo -e "$(GREEN_PRINT)$(CURRENT_TIME)"
	@echo -e "$(YELLOW_PRINT)"
	@echo -e $(1)
	@echo -e "$(NORMAL_TEXT)"
endef

.DEFAULT_GOAL := help

clean: ## Clean project
	$(call cecho,"Clean project")
	@clojure -A:pbuilder clean -f pbuild.edn

repl: ## Run Clojure REPL
	$(call cecho,"Run Clojure REPL")
	@clojure -A:repl

run: ## Run main function
	$(call cecho,"Run main function")
	@clojure -A:pbuilder

javac: ## Compile java classes
	$(call cecho,"Compile java classes")
	@clojure -A:pbuilder javac

compile: ## Compile Clojure code
	$(call cecho,"Compile clojure code")
	@clojure -A:pbuilder compile

jar: ## Build jar file (library)
	$(call cecho,"Build jar file (library)")
	@clojure -A:pbuilder jar

uberjar: ## Build ubejar file (executable)
	$(call cecho,"Build uberjar file (executable)")
	@clojure -A:pbuilder uberjar

install: ## Install jar file to local .m2
	$(call cecho,"Install jar file to local .m2")
	@clojure -A:pbuilder install

deploy: ## Deploy jar file to clojars
	$(call cecho,"Deploy jar file to clojars")
	@clojure -A:pbuilder deploy

conflicts: ## Show class conflicts (if any)
	$(call cecho,"Show class conflicts (if any)")
	@clojure -A:pbuilder conflicts

standalone: ## Create standalone executable bundle with custom JDK 9+
	$(call cecho,"Create standalone executable bundle with custom JDK 9+")
	@clojure -A:pbuilder standalone

help: ## Show help
	 @grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

