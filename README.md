[1. Recreate the site and migration in a docker container](#recreate-the-site-and-migration-in-a-docker-container)
[1.a to phpBB]
[1.b to MyBB]
[2. notes on adapting old database structure and default data types to modern requirements]
[3. test server Migration notes to MyBB]
[4. test server Migration notes to phpBB]



# Recreate the site and migration in a docker container
## MyBB test installation in docker container

This is a proposed directory structure:
```text
legacy-forums/
├── compose.yaml
├── Dockerfile
├── .env
├── apache/
│   └── forums.conf
├── php/
│   └── legacy.ini
├── db/
└── www/
    ├── ipboard/       # Your IP.Board 3.4.8 files
    └── mybb/          # MyBB 1.8.41 files
```

Create Dockerfile

Save this as legacy-forums/Dockerfile:
```dockerfile


FROM deminy/php-5.2:latest

# Add legacy PHP settings without replacing the image's default php.ini.
RUN mkdir -p /etc/php/apache2-php5.2/ext-active

COPY php/legacy.ini /etc/php/apache2-php5.2/ext-active/99-legacy.ini

# Configure Apache to serve both forums from /project.
COPY apache/forums.conf /etc/apache2/sites-enabled/000-project.conf

EXPOSE 80

CMD ["/usr/sbin/apache2ctl", "-D", "FOREGROUND"]
```


2. Docker Compose

Create `compose.yaml`:
```yaml
services:
  web:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: legacy-forums-web
    ports:
      - "127.0.0.1:8080:80"
    volumes:
      - ./www:/var/www/html
      - ./apache/forums.conf:/etc/apache2/sites-available/000-default.conf:ro
      - ./php/legacy.ini:/usr/local/etc/php/conf.d/legacy.ini:ro
    depends_on:
      db:
        condition: service_healthy
    networks:
      - forums

  db:
    image: mariadb:10.0.38
    container_name: legacy-forums-db
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
      MYSQL_DATABASE: mybb
      MYSQL_USER: forumuser
      MYSQL_PASSWORD: ${DB_PASSWORD}
    volumes:
      - db_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1",
             "-uroot", "-p$${MYSQL_ROOT_PASSWORD}"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - forums

networks:
  forums:
    driver: bridge

volumes:
  db_data:
```
.env
```
DB_ROOT_PASSWORD=replace_with_a_long_random_password
DB_PASSWORD=replace_with_another_long_random_password
```
Create `php/legacy.ini`:
```ini

date.timezone = Europe/Rome

display_errors = On
log_errors = On
error_reporting = E_ALL

; Reproduce the production restrictions
disable_functions = apache_child_terminate,dl,exec,imap_body,imap_createmailbox,imap_deletemailbox,imap_list,imap_open,imap_renamemailbox,inject_code,mb_send_mail,passthru,pcntl_alarm,pcntl_async_signals,pcntl_errno,pcntl_exec,pcntl_fork,pcntl_get_last_error,pcntl_getpriority,pcntl_setpriority,pcntl_signal,pcntl_signal_dispatch,pcntl_signal_get_handler,pcntl_sigprocmask,pcntl_sigtimedwait,pcntl_sigwaitinfo,pcntl_strerror,pcntl_wait,pcntl_waitpid,pcntl_wexitstatus,pcntl_wifcontinued,pcntl_wifexited,pcntl_wifsignaled,pcntl_wifstopped,pcntl_wstopsig,pcntl_wtermsig,popen,posix_getpwuid,posix_kill,posix_mkfifo,posix_setpgid,posix_setsid,posix_setuid,proc_close,proc_get_status,proc_nice,proc_open,proc_terminate,set_time_limit,shell_exec,symlink,system
```

Create apache/forums.conf:
```apache

<VirtualHost *:80>
    ServerName forums.local

    DocumentRoot /var/www/html

    <Directory /var/www/html>
        Options FollowSymLinks
        AllowOverride All
        Order allow,deny
        Allow from all
    </Directory>

    ErrorLog /dev/stderr
    CustomLog /dev/stdout combined
</VirtualHost>
```
For an initial test, both forums can live under the same document root:

    IP.Board: http://localhost:8080/ipboard/

    MyBB: http://localhost:8080/mybb/

You can later switch to separate virtual hosts to better match production.


Validate the Compose configuration
```bash
docker compose config
```
If that succeeds, Compose can parse the YAML and environment variables. It does not guarantee that the images exist or the containers will start.


4. Build and start the containers

Once both the Dockerfile and database image are ready, run:
bash

`docker compose up -d --build`




`chmod 777 ./cache/ ./cache/themes/ ./uploads/ ./uploads/avatars/ ./admin/backups/`

`chmod 666 ./inc/settings.php ./inc/config.php ./inc/languages/`

there are additional inputs for 666 based on language settings (maybe it will be required later)



## Mingration-notes-IP.Board-to-phpBB

1. rename root directory(containing index.php and other folders) to `src`


## Make the config file writable
chmod 666 config.php

## Make the required directories writable
chmod 777 cache/ files/ store/ images/avatars/upload/



## Docker container build and updates


## Check the avatar settings with old forum
set custom avatar size **General>AvatarSettings** and set the values at least 200px/200px and 50000 byte
