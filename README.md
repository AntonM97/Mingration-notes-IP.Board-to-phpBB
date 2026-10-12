[1. Recreate the site and migration in a docker container](#recreate-the-site-and-migration-in-a-docker-container)
[1.a to phpBB]
[1.b to MyBB]
[2. notes on adapting old database structure and default data types to modern requirements]
[3. test server Migration notes to MyBB]
[4. test server Migration notes to phpBB]



# Recreate the site and migration in a docker container




## Mingration-notes-IP.Board-to-phpBB

1. rename root directory(containing index.php and other folders) to `src`


## Make the config file writable
chmod 666 config.php

## Make the required directories writable
chmod 777 cache/ files/ store/ images/avatars/upload/



## Docker container build and updates


## Check the avatar settings with old forum
set custom avatar size **General>AvatarSettings** and set the values at least 200px/200px and 50000 byte
